package lk.coopfed.knoweb.m7customers.internal.account;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
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
import lk.coopfed.knoweb.m7customers.internal.customer.NicNumbers;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OpenAccount (27A section 6). Guards, in order: the society's OWN scope; the customer registered
 * by the caller's society and ACTIVE; the CREDIT_ACCOUNT consent in force; no account of this
 * society for the customer yet; a limit of zero or more, terms above zero, a cap of zero or more
 * (money in cents); when the limit is above zero, the NIC: well formed, the same as the one the
 * customer already has, and not held by another of the society's customers ({@code
 * m7.account.nic_held}: one person, one identity).
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
    private final CustomersClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    OpenAccountHandler(
            JdbcTemplate jdbc, ConfigRegistry config, CustomersClock clock, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.config = config;
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

        String nic = null;
        if (limit.signum() > 0 || (command.nic() != null && !command.nic().isBlank())) {
            nic = NicNumbers.normalise(command.nic())
                    .orElseThrow(() -> new ProblemException("m7.account.nic_required"));
            String hash = NicNumbers.hash(nic);
            String held = jdbc.queryForObject(
                    "select nic_hash from customers.customer where customer_id = ?", String.class, customerId);
            if (held != null && !held.equals(hash)) {
                throw new ProblemException("m7.account.nic_mismatch");
            }
            List<UUID> others = jdbc.queryForList(
                    "select customer_id from customers.customer where nic_hash = ? and customer_id <> ?",
                    UUID.class,
                    hash,
                    customerId);
            if (!others.isEmpty()) {
                throw new ProblemException(
                        "m7.account.nic_held",
                        Map.of("customerId", others.get(0).toString()));
            }
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
            jdbc.update(
                    "update customers.customer set nic_hash = ?, nic_last4 = ? where customer_id = ?",
                    NicNumbers.hash(nic),
                    NicNumbers.last4(nic),
                    customerId);
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
