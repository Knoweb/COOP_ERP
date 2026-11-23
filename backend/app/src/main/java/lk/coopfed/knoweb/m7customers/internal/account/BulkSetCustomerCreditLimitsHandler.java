package lk.coopfed.knoweb.m7customers.internal.account;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
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
import lk.coopfed.knoweb.m7customers.api.AccountLimitsAmended;
import lk.coopfed.knoweb.m7customers.api.BulkCustomerCreditLimitReport;
import lk.coopfed.knoweb.m7customers.api.BulkCustomerCreditLimitReport.Problem;
import lk.coopfed.knoweb.m7customers.api.BulkSetCustomerCreditLimits;
import lk.coopfed.knoweb.m7customers.api.BulkSetCustomerCreditLimits.CustomerLimitRow;
import lk.coopfed.knoweb.m7customers.api.CustomerCreditLimitsBulkImported;
import lk.coopfed.knoweb.m7customers.internal.customer.BulkCustomerCreditLimitCsvParser;
import lk.coopfed.knoweb.m7customers.internal.customer.CustomerGuards;
import lk.coopfed.knoweb.m7customers.internal.customer.NicHasher;
import lk.coopfed.knoweb.m7customers.internal.customer.NicNumbers;
import lk.coopfed.knoweb.m7customers.internal.customer.PersonalDataText;
import lk.coopfed.knoweb.m7customers.internal.customer.PhoneNumbers;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk command handler to set customer credit limits from a sheet or rows (CAT-14).
 *
 * <p>Enforces step-up authentication requiring fresh MFA before applying limits.
 * Operates with all-or-none validation within a single transaction boundary.
 */
@Service
@CommandHandler(permission = "cus.account.manage")
public class BulkSetCustomerCreditLimitsHandler
        implements Handles<BulkSetCustomerCreditLimits, BulkCustomerCreditLimitReport> {

    public static final String AUDIT_CUSTOMER_LIMITS_BULK_IMPORTED = "CUSTOMER_LIMITS_BULK_IMPORTED";

    private record AccountSnapshot(
            UUID accountId,
            UUID customerId,
            String accountNo,
            BigDecimal creditLimit,
            boolean hardBlock,
            BigDecimal offlineCap,
            String status) {}

    private record ValidatedAccountLimit(
            String identifier, AccountSnapshot account, BigDecimal creditLimit, String reason) {}

    private final JdbcTemplate jdbc;
    private final ConfigRegistry config;
    private final CustomersClock clock;
    private final NicHasher hasher;
    private final ObjectMapper json;
    private final AuditFacade audit;
    private final EventPublisher events;

    public BulkSetCustomerCreditLimitsHandler(
            JdbcTemplate jdbc,
            ConfigRegistry config,
            CustomersClock clock,
            NicHasher hasher,
            ObjectMapper json,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.config = config;
        this.clock = clock;
        this.hasher = hasher;
        this.json = json;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public BulkCustomerCreditLimitReport handle(BulkSetCustomerCreditLimits command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireOwnScope(scope);

        // 1. Mandatory Step-Up Authentication (MFA) Gate
        CustomerGuards.requireFreshMfa(config, clock, scope, "cus.account.manage");

        // 2. Parse sheet if provided
        List<CustomerLimitRow> rows = command.limits();
        if ((rows == null || rows.isEmpty())
                && command.sheet() != null
                && !command.sheet().isBlank()) {
            rows = BulkCustomerCreditLimitCsvParser.parse(command.sheet());
        }

        if (rows == null || rows.isEmpty()) {
            return new BulkCustomerCreditLimitReport(BulkCustomerCreditLimitReport.UPDATED, 0, List.of());
        }

        UUID society = scope.entityId();
        List<Problem> problems = new ArrayList<>();
        List<ValidatedAccountLimit> validated = new ArrayList<>();

        // 3. All-or-none validation phase
        for (int i = 0; i < rows.size(); i++) {
            CustomerLimitRow row = rows.get(i);
            String identifier = row.accountNo() != null && !row.accountNo().isBlank()
                    ? row.accountNo()
                    : (row.phone() != null && !row.phone().isBlank()
                            ? row.phone()
                            : (row.nic() != null && !row.nic().isBlank()
                                    ? row.nic()
                                    : (row.accountId() != null ? row.accountId().toString() : "row-" + (i + 1))));

            AccountSnapshot account = resolveAccount(row, society);
            if (account == null) {
                problems.add(new Problem(identifier, "account", "m7.account.not_found"));
                continue;
            }

            if ("CLOSED".equals(account.status())) {
                problems.add(new Problem(identifier, "account", "m7.account.closed"));
                continue;
            }

            if (row.creditLimit() == null
                    || row.creditLimit().compareTo(BigDecimal.ZERO) < 0
                    || row.creditLimit().scale() > 2) {
                problems.add(new Problem(identifier, "creditLimit", "m7.account.amount_invalid"));
                continue;
            }

            String rawReason = row.reason() != null && !row.reason().isBlank()
                    ? row.reason().trim()
                    : (command.defaultReason() != null ? command.defaultReason().trim() : "Bulk limit update");

            String reason;
            try {
                reason = PersonalDataText.require(CustomerGuards.requiredText(rawReason, "reason"), "reason");
            } catch (ProblemException e) {
                problems.add(new Problem(identifier, "reason", e.getMessage()));
                continue;
            }

            validated.add(new ValidatedAccountLimit(identifier, account, row.creditLimit(), reason));
        }

        if (!problems.isEmpty()) {
            return new BulkCustomerCreditLimitReport(BulkCustomerCreditLimitReport.REJECTED, 0, problems);
        }

        // 4. Mutation phase (all rows valid)
        Timestamp now = Timestamp.from(clock.now());
        BigDecimal totalLimit = BigDecimal.ZERO;
        int count = 0;

        for (ValidatedAccountLimit item : validated) {
            AccountSnapshot acct = item.account();
            BigDecimal newLimit = item.creditLimit();
            BigDecimal oldLimit = acct.creditLimit();

            jdbc.update(
                    "update customers.customer_account set credit_limit = ? where account_id = ?",
                    newLimit,
                    acct.accountId());

            Map<String, Object> before = limits(oldLimit, acct.hardBlock(), acct.offlineCap());
            Map<String, Object> after = limits(newLimit, acct.hardBlock(), acct.offlineCap());
            after.put("nicCaptured", false);

            jdbc.update(
                    """
                    insert into customers.account_history (history_id, account_id, action, before_value, after_value, reason,
                        changed_by, changed_at, owner_entity_id)
                    values (?, ?, 'LIMITS_AMENDED', ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                    """,
                    Ids.next(),
                    acct.accountId(),
                    toJsonText(before),
                    toJsonText(after),
                    item.reason(),
                    scope.userId(),
                    now,
                    society);

            audit.record(
                    AmendAccountLimitsHandler.AUDIT_AMENDED,
                    Subject.of("customer_account", acct.accountId()),
                    before,
                    after,
                    scope,
                    item.reason());

            events.publish(new AccountLimitsAmended(
                    acct.accountId(), acct.customerId(), society, newLimit, acct.hardBlock(), acct.offlineCap()));

            totalLimit = totalLimit.add(newLimit);
            count++;
        }

        // Zero-PII bulk audit and domain event
        audit.record(
                AUDIT_CUSTOMER_LIMITS_BULK_IMPORTED,
                Subject.of("entity", society),
                null,
                Map.of("count", count, "totalCreditLimit", totalLimit.toPlainString()),
                scope);

        events.publish(new CustomerCreditLimitsBulkImported(society, count, totalLimit));

        return new BulkCustomerCreditLimitReport(BulkCustomerCreditLimitReport.UPDATED, count, List.of());
    }

    private AccountSnapshot resolveAccount(CustomerLimitRow row, UUID society) {
        if (row.accountId() != null) {
            List<AccountSnapshot> list = jdbc.query(
                    """
                    select account_id, customer_id, account_no, credit_limit, hard_block, offline_cap, status
                      from customers.customer_account
                     where account_id = ? and owner_entity_id = ?
                    """,
                    (rs, n) -> new AccountSnapshot(
                            rs.getObject("account_id", UUID.class),
                            rs.getObject("customer_id", UUID.class),
                            rs.getString("account_no"),
                            rs.getBigDecimal("credit_limit"),
                            rs.getBoolean("hard_block"),
                            rs.getBigDecimal("offline_cap"),
                            rs.getString("status")),
                    row.accountId(),
                    society);
            if (!list.isEmpty()) {
                return list.get(0);
            }
        }

        if (row.accountNo() != null && !row.accountNo().isBlank()) {
            List<AccountSnapshot> list = jdbc.query(
                    """
                    select account_id, customer_id, account_no, credit_limit, hard_block, offline_cap, status
                      from customers.customer_account
                     where account_no = ? and owner_entity_id = ?
                    """,
                    (rs, n) -> new AccountSnapshot(
                            rs.getObject("account_id", UUID.class),
                            rs.getObject("customer_id", UUID.class),
                            rs.getString("account_no"),
                            rs.getBigDecimal("credit_limit"),
                            rs.getBoolean("hard_block"),
                            rs.getBigDecimal("offline_cap"),
                            rs.getString("status")),
                    row.accountNo().trim(),
                    society);
            if (!list.isEmpty()) {
                return list.get(0);
            }
        }

        if (row.phone() != null && !row.phone().isBlank()) {
            String normPhone = PhoneNumbers.normalise(row.phone()).orElse(null);
            if (normPhone != null) {
                List<AccountSnapshot> list = jdbc.query(
                        """
                        select ca.account_id, ca.customer_id, ca.account_no, ca.credit_limit, ca.hard_block, ca.offline_cap, ca.status
                          from customers.customer_phone cp
                          join customers.customer_account ca on ca.customer_id = cp.customer_id and ca.owner_entity_id = cp.owner_entity_id
                         where cp.phone = ? and cp.owner_entity_id = ? and cp.is_primary is true
                         order by ca.opened_at desc limit 1
                        """,
                        (rs, n) -> new AccountSnapshot(
                                rs.getObject("account_id", UUID.class),
                                rs.getObject("customer_id", UUID.class),
                                rs.getString("account_no"),
                                rs.getBigDecimal("credit_limit"),
                                rs.getBoolean("hard_block"),
                                rs.getBigDecimal("offline_cap"),
                                rs.getString("status")),
                        normPhone,
                        society);
                if (!list.isEmpty()) {
                    return list.get(0);
                }
            }
        }

        if (row.nic() != null && !row.nic().isBlank()) {
            String canonicalNic = NicNumbers.canonical(row.nic()).orElse(null);
            if (canonicalNic != null) {
                String[] candidates = hasher.candidates(canonicalNic).toArray(String[]::new);
                List<UUID> customerIds = jdbc.query(
                        "select own_customer_id from customers.nic_holders(cast(? as char(64)[])) where own_customer_id is not null",
                        (rs, n) -> rs.getObject("own_customer_id", UUID.class),
                        (Object) candidates);
                if (!customerIds.isEmpty()) {
                    UUID custId = customerIds.get(0);
                    List<AccountSnapshot> list = jdbc.query(
                            """
                            select account_id, customer_id, account_no, credit_limit, hard_block, offline_cap, status
                              from customers.customer_account
                             where customer_id = ? and owner_entity_id = ?
                             order by opened_at desc limit 1
                            """,
                            (rs, n) -> new AccountSnapshot(
                                    rs.getObject("account_id", UUID.class),
                                    rs.getObject("customer_id", UUID.class),
                                    rs.getString("account_no"),
                                    rs.getBigDecimal("credit_limit"),
                                    rs.getBoolean("hard_block"),
                                    rs.getBigDecimal("offline_cap"),
                                    rs.getString("status")),
                            custId,
                            society);
                    if (!list.isEmpty()) {
                        return list.get(0);
                    }
                }
            }
        }

        return null;
    }

    private static Map<String, Object> limits(BigDecimal creditLimit, boolean hardBlock, BigDecimal offlineCap) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("creditLimit", creditLimit.toPlainString());
        map.put("hardBlock", hardBlock);
        map.put("offlineCap", offlineCap.toPlainString());
        return map;
    }

    private String toJsonText(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize account limits to JSON", e);
        }
    }
}
