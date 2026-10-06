package lk.coopfed.knoweb.m7customers.internal.account;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.AccountOpened;
import lk.coopfed.knoweb.m7customers.api.OpenAccount;
import lk.coopfed.knoweb.m7customers.internal.customer.CustomerGuards;
import lk.coopfed.knoweb.m7customers.internal.customer.NicCapture;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OpenAccount (27A section 6). Guards, in order: the society's OWN scope; the customer registered
 * by the caller's society and ACTIVE; the CREDIT_ACCOUNT consent in force; no account of this
 * society for the customer yet; a limit of zero or more, terms above zero, a cap of zero or more
 * (money in cents); when the limit is above {@code customers.nic_required_above_limit}, the NIC
 * ({@link NicCapture}): well formed, the same card as the one the customer already has, and held by
 * nobody else at any society ({@code m7.account.nic_held}, {@code m7.account.nic_held_elsewhere}:
 * one person, one identity).
 *
 * <p>Mutation: the account (OPEN, balance zero, the next account number of the society), the NIC's
 * hash and last four on the customer. Audit ACCOUNT_OPENED with the limit, terms and cap and
 * whether a NIC was captured (never the NIC); event account.opened.v1.
 *
 * <p>27A asks for MFA on this command; the step-up waits for the limit screens (README,
 * "Deviations"), so the permission is checked but no fresh second factor.
 */
@Service
@CommandHandler(permission = "cus.account.manage")
class OpenAccountHandler implements Handles<OpenAccount, UUID> {

    static final String AUDIT_OPENED = "ACCOUNT_OPENED";
    static final String TERMS_KEY = "customers.default_terms_days";
    static final String CAP_KEY = "customers.offline_account_cap";

    private final JdbcTemplate jdbc;
    private final ConfigRegistry config;
    private final NicCapture nicCapture;
    private final CustomersClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    OpenAccountHandler(
            JdbcTemplate jdbc,
            ConfigRegistry config,
            NicCapture nicCapture,
            CustomersClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.config = config;
        this.nicCapture = nicCapture;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(OpenAccount command, ScopeContext scope) {
        if (command == null || command.customerId() == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireOwnScope(scope);
        UUID customerId = command.customerId();
        UUID society = scope.entityId();
        if (!"ACTIVE".equals(CustomerGuards.customerStatus(jdbc, customerId))) {
            throw new ProblemException("m7.customer.not_active");
        }
        Integer consents = jdbc.queryForObject(
                """
                select count(*) from customers.customer_consent
                 where customer_id = ? and purpose = 'CREDIT_ACCOUNT' and withdrawn_at is null
                """,
                Integer.class,
                customerId);
        if (consents == null || consents == 0) {
            throw new ProblemException("m7.customer.consent_required");
        }
        Integer existing = jdbc.queryForObject(
                "select count(*) from customers.customer_account where customer_id = ? and owner_entity_id = ?",
                Integer.class,
                customerId,
                society);
        if (existing != null && existing > 0) {
            throw new ProblemException("m7.account.exists");
        }
        BigDecimal limit =
                money(command.creditLimit() == null ? BigDecimal.ZERO : command.creditLimit(), "creditLimit");
        int terms = command.termsDays() == null ? config.getInt(TERMS_KEY, scope, 30) : command.termsDays();
        if (terms <= 0) {
            throw new ProblemException("m7.account.terms_invalid");
        }
        BigDecimal cap = money(
                command.offlineCap() == null
                        ? new BigDecimal(config.getOrDefault(CAP_KEY, scope, "5000"))
                        : command.offlineCap(),
                "offlineCap");

        // The NIC when the limit is above customers.nic_required_above_limit (doc 27 section 7;
        // wave 2, M7CR-03), or whenever one is offered: canonical form, the same card as the one
        // recorded, held by nobody else at any society (NicCapture).
        NicCapture.Captured nic = null;
        if (limit.compareTo(CustomerGuards.nicRequiredAboveLimit(config, scope)) > 0
                || (command.nic() != null && !command.nic().isBlank())) {
            nic = nicCapture.capture(command.nic(), customerId);
        }

        // The society's next account number, one at a time (the unique constraint is the safety net).
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?))", "customers.account_no " + society);
        Integer last = jdbc.queryForObject(
                """
                select coalesce(max(substring(account_no from 2)::integer), 0) from customers.customer_account
                 where owner_entity_id = ? and account_no ~ '^A[0-9]+$'
                """,
                Integer.class,
                society);
        String accountNo = String.format("A%05d", (last == null ? 0 : last) + 1);

        UUID accountId = Ids.next();
        jdbc.update(
                """
                insert into customers.customer_account (account_id, customer_id, account_no, credit_limit, balance,
                    offline_cap, terms_days, hard_block, status, opened_at, opened_by, owner_entity_id)
                values (?, ?, ?, ?, 0, ?, ?, false, 'OPEN', ?, ?, ?)
                """,
                accountId,
                customerId,
                accountNo,
                limit,
                cap,
                terms,
                Timestamp.from(clock.now()),
                scope.userId() == null ? society : scope.userId(),
                society);
        if (nic != null) {
            jdbc.update(NicCapture.WRITE_SQL, nic.hash(), nic.last4(), nic.keyId(), customerId);
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("customerId", customerId);
        after.put("accountNo", accountNo);
        after.put("creditLimit", limit);
        after.put("termsDays", terms);
        after.put("offlineCap", cap);
        after.put("nicCaptured", nic != null);
        after.put("status", "OPEN");
        audit.record(AUDIT_OPENED, Subject.of("customer_account", accountId), null, after, scope);
        events.publish(new AccountOpened(accountId, customerId, society, limit));
        return accountId;
    }

    private static BigDecimal money(BigDecimal amount, String field) {
        if (amount.signum() < 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new ProblemException("m7.account.amount_invalid", Map.of("field", field));
        }
        return amount.setScale(2);
    }
}
