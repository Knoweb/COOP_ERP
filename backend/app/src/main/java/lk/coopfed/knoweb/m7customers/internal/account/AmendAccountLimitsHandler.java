package lk.coopfed.knoweb.m7customers.internal.account;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
import lk.coopfed.knoweb.m7customers.api.AccountLimitsAmended;
import lk.coopfed.knoweb.m7customers.api.AmendAccountLimits;
import lk.coopfed.knoweb.m7customers.internal.customer.CustomerGuards;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import lk.coopfed.knoweb.m7customers.internal.ledger.Ledger;
import lk.coopfed.knoweb.m7customers.internal.ledger.Ledger.LockedAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AmendLimit / SetHardBlock / SetOfflineCap (27A section 6; doc 27 section 4.2). Guards, in order:
 * the society's OWN scope; the account of this society ({@code m7.account.not_found}), not CLOSED
 * ({@code m7.account.closed}); a reason; each amount zero or more in cents; something changes
 * ({@code m7.account.limits_unchanged}); when the limit goes up, a second factor presented within
 * {@code customers.limit_increase_mfa_max_age} ({@code mfa.required}; doc 27 section 4.2: "MFA
 * for limit increases"), as M1 asks for an entity's credit limit.
 *
 * <p>Mutation: the account's limit, hard block and offline cap; a LIMITS_AMENDED row in the
 * account's history with before, after and reason. The limit is never a reason to refuse a till's
 * charge afterwards: a charge beyond it is posted and flagged (AGENTS.md; PostAccountTender).
 * Audit ACCOUNT_LIMIT_AMENDED with before and after; event account.limit_amended.v1.
 */
@Service
@CommandHandler(permission = "cus.account.manage")
class AmendAccountLimitsHandler implements Handles<AmendAccountLimits, UUID> {

    static final String AUDIT_AMENDED = "ACCOUNT_LIMIT_AMENDED";
    static final String MFA_MAX_AGE = "customers.limit_increase_mfa_max_age";
    static final Duration DEFAULT_MFA_MAX_AGE = Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final Ledger ledger;
    private final ConfigRegistry config;
    private final CustomersClock clock;
    private final ObjectMapper json;
    private final AuditFacade audit;
    private final EventPublisher events;

    AmendAccountLimitsHandler(
            JdbcTemplate jdbc,
            Ledger ledger,
            ConfigRegistry config,
            CustomersClock clock,
            ObjectMapper json,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.config = config;
        this.clock = clock;
        this.json = json;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(AmendAccountLimits command, ScopeContext scope) {
        if (command == null || command.accountId() == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireOwnScope(scope);
        LockedAccount account =
                ledger.lock(command.accountId()).orElseThrow(() -> new ProblemException("m7.account.not_found"));
        if ("CLOSED".equals(account.status())) {
            throw new ProblemException("m7.account.closed");
        }
        String reason = CustomerGuards.requiredText(command.reason(), "reason");
        BigDecimal limit = command.creditLimit() == null
                ? account.creditLimit()
                : CustomerGuards.money(command.creditLimit(), "creditLimit");
        boolean hardBlock = command.hardBlock() == null ? account.hardBlock() : command.hardBlock();
        BigDecimal cap = command.offlineCap() == null
                ? account.offlineCap()
                : CustomerGuards.money(command.offlineCap(), "offlineCap");
        if (limit.compareTo(account.creditLimit()) == 0
                && hardBlock == account.hardBlock()
                && sameAmount(cap, account.offlineCap())) {
            throw new ProblemException("m7.account.limits_unchanged");
        }
        if (limit.compareTo(account.creditLimit()) > 0) {
            requireFreshMfa(scope);
        }

        jdbc.update(
                "update customers.customer_account set credit_limit = ?, hard_block = ?, offline_cap = ? where account_id = ?",
                limit,
                hardBlock,
                cap,
                account.accountId());
        Map<String, Object> before = limits(account.creditLimit(), account.hardBlock(), account.offlineCap());
        Map<String, Object> after = limits(limit, hardBlock, cap);
        jdbc.update(
                """
                insert into customers.account_history (history_id, account_id, action, before_value, after_value, reason,
                    changed_by, changed_at, owner_entity_id)
                values (?, ?, 'LIMITS_AMENDED', ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                """,
                Ids.next(),
                account.accountId(),
                text(before),
                text(after),
                reason,
                scope.userId(),
                Timestamp.from(clock.now()),
                account.ownerEntityId());

        audit.record(AUDIT_AMENDED, Subject.of("customer_account", account.accountId()), before, after, scope, reason);
        events.publish(new AccountLimitsAmended(
                account.accountId(), account.customerId(), account.ownerEntityId(), limit, hardBlock, cap));
        return account.accountId();
    }

    /** The step-up of doc 27 section 4.2: a second factor presented within the configured age. */
    private void requireFreshMfa(ScopeContext scope) {
        Duration maxAge = config.getDuration(MFA_MAX_AGE, scope, DEFAULT_MFA_MAX_AGE);
        Instant freshEnough = clock.now().minus(maxAge);
        if (scope.mfaAt() == null || scope.mfaAt().isBefore(freshEnough)) {
            throw new ProblemException("mfa.required", Map.of("permission", "cus.account.manage"));
        }
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? Objects.equals(a, b) : a.compareTo(b) == 0;
    }

    private static Map<String, Object> limits(BigDecimal limit, boolean hardBlock, BigDecimal cap) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("creditLimit", limit);
        values.put("hardBlock", hardBlock);
        values.put("offlineCap", cap);
        return values;
    }

    private String text(Map<String, Object> values) {
        try {
            return json.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
