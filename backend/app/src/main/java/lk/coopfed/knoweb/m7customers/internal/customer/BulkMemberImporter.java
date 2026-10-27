package lk.coopfed.knoweb.m7customers.internal.customer;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import lk.coopfed.knoweb.m7customers.api.BulkImportMembers;
import lk.coopfed.knoweb.m7customers.api.BulkMemberReport;
import lk.coopfed.knoweb.m7customers.api.ConsentRecorded;
import lk.coopfed.knoweb.m7customers.api.CustomerRegistered;
import lk.coopfed.knoweb.m7customers.api.CustomerUpdated;
import lk.coopfed.knoweb.m7customers.api.MembersBulkImported;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads members/customers in bulk from a sheet or list of member rows (CAT-10).
 *
 * <p>Operates within a single transactional boundary. Validates rows, checks phone reuse
 * and honours confirmation prompts (NEEDS_CONFIRMATION), handles existing members matched
 * by NIC or phone without failing the batch, captures NIC hashes, and records audit and domain events.
 */
@Service
@CommandHandler(permission = "cus.customer.register")
public class BulkMemberImporter implements Handles<BulkImportMembers, BulkMemberReport> {

    public static final String AUDIT_MEMBERS_BULK_IMPORTED = "MEMBERS_BULK_IMPORTED";
    private static final String TERMS_KEY = "customers.default_terms_days";
    private static final String CAP_KEY = "customers.offline_account_cap";

    private final JdbcTemplate jdbc;
    private final ReuseDetector reuse;
    private final NicCapture nicCapture;
    private final NicHasher hasher;
    private final ConfigRegistry config;
    private final CustomersClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    BulkMemberImporter(
            JdbcTemplate jdbc,
            ReuseDetector reuse,
            NicCapture nicCapture,
            NicHasher hasher,
            ConfigRegistry config,
            CustomersClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.reuse = reuse;
        this.nicCapture = nicCapture;
        this.hasher = hasher;
        this.config = config;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public BulkMemberReport handle(BulkImportMembers command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireOwnScope(scope);

        List<BulkImportMembers.MemberRow> rows = command.members();
        if ((rows == null || rows.isEmpty())
                && command.sheet() != null
                && !command.sheet().isBlank()) {
            rows = BulkMemberCsvParser.parse(command.sheet());
        }

        if (rows == null || rows.isEmpty()) {
            return new BulkMemberReport(Ids.next(), BulkMemberReport.EMPTY, 0, 0, 0, 0, List.of());
        }

        UUID society = scope.entityId();
        Timestamp now = Timestamp.from(clock.now());

        Set<String> seenPhones = new HashSet<>();
        Set<String> seenNics = new HashSet<>();

        int registeredCount = 0;
        int updatedCount = 0;

        for (int r = 0; r < rows.size(); r++) {
            BulkImportMembers.MemberRow row = rows.get(r);
            String name = CustomerGuards.requiredText(row.displayName(), "displayName");
            String language = CustomerGuards.language(row.language());

            String phone = PhoneNumbers.normalise(row.phone())
                    .orElseThrow(() -> new ProblemException("m7.customer.phone_invalid"));

            if (!seenPhones.add(phone)) {
                throw new ProblemException("m7.customer.phone_held");
            }

            String canonicalNic = null;
            if (row.nic() != null && !row.nic().isBlank()) {
                canonicalNic = NicNumbers.canonical(row.nic())
                        .orElseThrow(() -> new ProblemException("m7.account.nic_required"));
                if (!seenNics.add(canonicalNic)) {
                    throw new ProblemException("m7.account.nic_held");
                }
            }

            List<String> consents = row.consents() == null || row.consents().isEmpty()
                    ? List.of(RegisterCustomerHandler.CREDIT_ACCOUNT)
                    : row.consents().stream().distinct().toList();
            for (String purpose : consents) {
                if (!RegisterCustomerHandler.PURPOSES.contains(purpose)) {
                    throw new ProblemException(
                            "m7.customer.consent_invalid", Map.of("purpose", String.valueOf(purpose)));
                }
            }
            if (!consents.contains(RegisterCustomerHandler.CREDIT_ACCOUNT)) {
                throw new ProblemException("m7.customer.consent_required");
            }

            List<String> tags = CustomerGuards.tags(row.tags());
            boolean confirmed = row.confirmedIdentity() || command.confirmedReuse();

            // Match existing member in caller's society by NIC or primary phone
            UUID existingCustomerId = null;
            if (canonicalNic != null) {
                String[] candidates = hasher.candidates(canonicalNic).toArray(String[]::new);
                List<UUID> nicHolders = jdbc.query(
                        "select own_customer_id from customers.nic_holders(cast(? as char(64)[])) where own_customer_id is not null",
                        (rs, n) -> rs.getObject("own_customer_id", UUID.class),
                        (Object) candidates);
                if (!nicHolders.isEmpty()) {
                    existingCustomerId = nicHolders.get(0);
                }
            }
            if (existingCustomerId == null) {
                List<UUID> phoneHolders = jdbc.query(
                        "select own_customer_id from customers.phone_holders(?) where held_by_caller is true and valid_to is null and own_customer_id is not null",
                        (rs, n) -> rs.getObject("own_customer_id", UUID.class),
                        phone);
                if (!phoneHolders.isEmpty()) {
                    existingCustomerId = phoneHolders.get(0);
                }
            }

            if (existingCustomerId != null) {
                // Member already exists in caller's society: honour reuse logic for that customer
                reuse.guard(phone, existingCustomerId, confirmed, scope);

                if (row.nic() != null && !row.nic().isBlank()) {
                    NicCapture.Captured captured = nicCapture.capture(row.nic(), existingCustomerId);
                    jdbc.update(
                            NicCapture.WRITE_SQL,
                            captured.hash(),
                            captured.last4(),
                            captured.keyId(),
                            existingCustomerId);
                }

                jdbc.update(
                        """
                        update customers.customer
                           set display_name = ?,
                               display_name_si = coalesce(?, display_name_si),
                               display_name_ta = coalesce(?, display_name_ta),
                               language = ?
                         where customer_id = ?
                        """,
                        name,
                        CustomerGuards.blankToNull(row.displayNameSi()),
                        CustomerGuards.blankToNull(row.displayNameTa()),
                        language,
                        existingCustomerId);

                for (String tag : tags) {
                    jdbc.update(
                            "insert into customers.customer_tag (customer_id, tag_code, owner_entity_id, tagged_at) values (?, ?, ?, ?) on conflict do nothing",
                            existingCustomerId,
                            tag,
                            society,
                            now);
                }

                if (row.creditLimit() != null) {
                    Integer acctCount = jdbc.queryForObject(
                            "select count(*) from customers.customer_account where customer_id = ? and owner_entity_id = ?",
                            Integer.class,
                            existingCustomerId,
                            society);
                    if (acctCount == null || acctCount == 0) {
                        openAccount(
                                existingCustomerId,
                                row.creditLimit(),
                                row.termsDays(),
                                row.offlineCap(),
                                scope,
                                society,
                                now);
                    }
                }

                Map<String, Object> afterUpdate = new LinkedHashMap<>();
                afterUpdate.put("displayName", name);
                afterUpdate.put("language", language);
                afterUpdate.put("tags", tags);
                afterUpdate.put("status", "ACTIVE");
                audit.record(
                        RegisterCustomerHandler.AUDIT_REGISTERED,
                        Subject.of("customer", existingCustomerId),
                        null,
                        afterUpdate,
                        scope);
                events.publish(new CustomerUpdated(existingCustomerId, society));
                updatedCount++;
            } else {
                // New member registration with reuse prompt detection
                ReuseDetector.Confirmed previousHolders = reuse.guard(phone, null, confirmed, scope);

                UUID customerId = Ids.next();
                NicCapture.Captured nicCaptured = null;
                if (row.nic() != null && !row.nic().isBlank()) {
                    nicCaptured = nicCapture.capture(row.nic(), customerId);
                }

                jdbc.update(
                        """
                        insert into customers.customer (customer_id, display_name, display_name_si, display_name_ta, language,
                            nic_hash, nic_last4, nic_key_id, attributes, status, registered_by_entity_id, registered_at, owner_entity_id)
                        values (?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, 'ACTIVE', ?, ?, ?)
                        """,
                        customerId,
                        name,
                        CustomerGuards.blankToNull(row.displayNameSi()),
                        CustomerGuards.blankToNull(row.displayNameTa()),
                        language,
                        nicCaptured == null ? null : nicCaptured.hash(),
                        nicCaptured == null ? null : nicCaptured.last4(),
                        nicCaptured == null ? null : nicCaptured.keyId(),
                        society,
                        now,
                        society);

                jdbc.update(
                        """
                        insert into customers.customer_phone (phone_id, customer_id, phone, is_primary, valid_from, changed_by,
                            reason, owner_entity_id)
                        values (?, ?, ?, true, ?, ?, 'REGISTERED', ?)
                        """,
                        Ids.next(),
                        customerId,
                        phone,
                        now,
                        scope.userId(),
                        society);

                for (String purpose : consents) {
                    jdbc.update(
                            """
                            insert into customers.customer_consent (consent_id, customer_id, purpose, granted_at, granted_via,
                                entity_id, owner_entity_id)
                            values (?, ?, ?, ?, 'WEB', ?, ?)
                            """,
                            Ids.next(),
                            customerId,
                            purpose,
                            now,
                            society,
                            society);
                }

                for (String tag : tags) {
                    jdbc.update(
                            "insert into customers.customer_tag (customer_id, tag_code, owner_entity_id, tagged_at) values (?, ?, ?, ?)",
                            customerId,
                            tag,
                            society,
                            now);
                }

                if (row.creditLimit() != null) {
                    openAccount(customerId, row.creditLimit(), row.termsDays(), row.offlineCap(), scope, society, now);
                }

                Map<String, Object> after = new LinkedHashMap<>();
                after.put("language", language);
                after.put("via", "WEB");
                after.put("consents", consents);
                after.put("tags", tags);
                after.put("status", "ACTIVE");
                previousHolders.describe(after);
                audit.record(
                        RegisterCustomerHandler.AUDIT_REGISTERED,
                        Subject.of("customer", customerId),
                        null,
                        after,
                        scope);

                events.publish(new CustomerRegistered(customerId, society));
                for (String purpose : consents) {
                    events.publish(new ConsentRecorded(customerId, society, purpose));
                }
                registeredCount++;
            }
        }

        UUID importId = Ids.next();
        Map<String, Object> batchAudit = new LinkedHashMap<>();
        batchAudit.put("totalRows", rows.size());
        batchAudit.put("registered", registeredCount);
        batchAudit.put("updated", updatedCount);
        audit.record(AUDIT_MEMBERS_BULK_IMPORTED, Subject.of("customers", importId), null, batchAudit, scope);
        events.publish(new MembersBulkImported(importId, society, rows.size(), registeredCount, updatedCount));

        return new BulkMemberReport(
                importId, BulkMemberReport.IMPORTED, rows.size(), registeredCount, updatedCount, 0, List.of());
    }

    private void openAccount(
            UUID customerId,
            BigDecimal creditLimit,
            Integer termsDays,
            BigDecimal offlineCap,
            ScopeContext scope,
            UUID society,
            Timestamp now) {
        BigDecimal limit = CustomerGuards.money(creditLimit == null ? BigDecimal.ZERO : creditLimit, "creditLimit");
        int terms = termsDays == null ? config.getInt(TERMS_KEY, scope, 30) : termsDays;
        if (terms <= 0) {
            throw new ProblemException("m7.account.terms_invalid");
        }
        BigDecimal cap = CustomerGuards.money(
                offlineCap == null ? new BigDecimal(config.getOrDefault(CAP_KEY, scope, "5000")) : offlineCap,
                "offlineCap");

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
                now,
                scope.userId(),
                society);

        Map<String, Object> afterAccount = new LinkedHashMap<>();
        afterAccount.put("accountNo", accountNo);
        afterAccount.put("creditLimit", limit);
        afterAccount.put("termsDays", terms);
        afterAccount.put("offlineCap", cap);
        audit.record("ACCOUNT_OPENED", Subject.of("account", accountId), null, afterAccount, scope);
        events.publish(new AccountOpened(accountId, customerId, society, limit));
    }
}
