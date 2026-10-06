package lk.coopfed.knoweb.m7customers.internal.privacy;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m7customers.api.CustomerAnonymised;
import lk.coopfed.knoweb.m7customers.api.DataSubjectRequestFulfilled;
import lk.coopfed.knoweb.m7customers.api.FulfilDataSubjectRequest;
import lk.coopfed.knoweb.m7customers.api.RecordDataSubjectRequest;
import lk.coopfed.knoweb.m7customers.internal.customer.CustomerGuards;
import lk.coopfed.knoweb.m7customers.internal.customer.PersonalDataText;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FulfilAccess / FulfilErasure, and the fulfilment of a correction (27A section 6; doc 27 section
 * 4.4). Guards, in order: the society's OWN scope; the request of this society ({@code
 * m7.privacy.not_found}), RECEIVED ({@code m7.privacy.not_received}); the caller is the society's
 * responsible officer ({@code m7.privacy.no_officer}, {@code m7.privacy.officer_only}); the second
 * factor is the permission's ({@code cus.privacy.fulfil} requires MFA); the outcome free of a phone
 * number or NIC ({@code m7.field.personal_data}). Then by kind:
 * <ul>
 *   <li>ACCESS: the customer is not anonymised; the export ({@link PrivacyExporter}) is what the
 *       officer hands over ({@code DownloadAccessExport}), and its SHA-256 at fulfilment is kept
 *       on the request;</li>
 *   <li>CORRECTION: what was corrected is written down ({@code outcome}); the correction itself is
 *       an ordinary amendment of the customer (AmendCustomer, ChangePhone), audited there;</li>
 *   <li>ERASURE (wave 2, M7CR-09, M7CR-10; CR-27A-1 item 3): every account of the customer at every
 *       society is locked for the transaction through {@code customers.lock_accounts_for_erasure}
 *       and must be CLOSED ({@code m7.privacy.account_open}) at zero balance ({@code
 *       m7.privacy.open_balance}: a till fact may land on a closed account) and closed more than
 *       {@code customers.erasure_wait_days} ago ({@code m7.privacy.recently_closed}: a shop sells up
 *       to two days offline, and its facts must have arrived). Then the Anonymiser (27A section
 *       6.4): the name becomes "Customer", the other names, the NIC's hash, key id and last four and
 *       the attributes go, the status is ANONYMISED; every phone row is closed with its number
 *       replaced; the consents withdrawn; the tags removed; every data-subject request of the
 *       customer loses its notes and reads ERASED as its outcome, this one ANONYMISED (the command's
 *       text is ignored). The postings, allocations, payment receipts, the account with its history
 *       and the adjustment reasons stay (doc 10 L-05: the society's accounting records, keyed by
 *       the opaque customer id). A till fact that lands afterwards is posted and flagged
 *       (PostAccountTender) and settled through REOPEN: doc 27 section 3.2's documented limitation.</li>
 * </ul>
 * Mutation: the request FULFILLED with who, when and the outcome. Audit DSAR_FULFILLED, and
 * CUSTOMER_ANONYMISED for an erasure (its record names the request id only); events
 * dsar.fulfilled.v1 and customer.anonymised.v1.
 */
@Service
@CommandHandler(permission = "cus.privacy.fulfil")
class FulfilDataSubjectRequestHandler implements Handles<FulfilDataSubjectRequest, UUID> {

    static final String AUDIT_FULFILLED = "DSAR_FULFILLED";
    static final String AUDIT_ANONYMISED = "CUSTOMER_ANONYMISED";
    static final String ANONYMOUS_NAME = "Customer";
    static final String ERASED_PHONE = "ERASED";
    static final String ERASED_OUTCOME = "ERASED";
    static final String ANONYMISED_OUTCOME = "ANONYMISED";
    static final String WAIT_DAYS = "customers.erasure_wait_days";
    static final int DEFAULT_WAIT_DAYS = 3;

    private final JdbcTemplate jdbc;
    private final PartyQueries parties;
    private final PrivacyExporter exporter;
    private final ConfigRegistry config;
    private final CustomersClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    FulfilDataSubjectRequestHandler(
            JdbcTemplate jdbc,
            PartyQueries parties,
            PrivacyExporter exporter,
            ConfigRegistry config,
            CustomersClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.parties = parties;
        this.exporter = exporter;
        this.config = config;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(FulfilDataSubjectRequest command, ScopeContext scope) {
        if (command == null || command.requestId() == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireOwnScope(scope);
        PrivacyGuards.Request request = PrivacyGuards.lockRequest(jdbc, command.requestId());
        if (!"RECEIVED".equals(request.status())) {
            throw new ProblemException("m7.privacy.not_received", Map.of("status", request.status()));
        }
        PrivacyGuards.requireResponsibleOfficer(parties, scope);
        String status = CustomerGuards.customerStatus(jdbc, request.customerId());
        String outcome = PersonalDataText.require(CustomerGuards.blankToNull(command.outcome()), "outcome");
        String exportSha = null;
        switch (request.kind()) {
            case RecordDataSubjectRequest.ACCESS -> {
                if ("ANONYMISED".equals(status)) {
                    throw new ProblemException("m7.customer.anonymised");
                }
                exportSha = exporter.sha256(exporter.export(request.customerId()));
            }
            case RecordDataSubjectRequest.CORRECTION -> outcome = CustomerGuards.requiredText(outcome, "outcome");
            default -> {
                if ("ANONYMISED".equals(status)) {
                    throw new ProblemException("m7.customer.anonymised");
                }
                requireEveryAccountSettledAndClosed(request.customerId(), scope);
                anonymise(request.customerId());
                outcome = ANONYMISED_OUTCOME;
            }
        }

        jdbc.update(
                """
                update customers.data_subject_request
                   set status = 'FULFILLED', fulfilled_at = ?, fulfilled_by = ?, outcome = ?, export_sha256 = ?
                 where request_id = ?
                """,
                Timestamp.from(clock.now()),
                scope.userId(),
                outcome,
                exportSha,
                request.requestId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("customerId", request.customerId());
        after.put("kind", request.kind());
        after.put("status", "FULFILLED");
        after.put("exportSha256", exportSha);
        Subject subject = Subject.of("data_subject_request", request.requestId());
        audit.record(AUDIT_FULFILLED, subject, Map.of("status", "RECEIVED"), after, scope);
        if (RecordDataSubjectRequest.ERASURE.equals(request.kind())) {
            audit.record(
                    AUDIT_ANONYMISED,
                    Subject.of("customer", request.customerId()),
                    null,
                    Map.of("requestId", request.requestId()),
                    scope);
            events.publish(new CustomerAnonymised(request.customerId(), request.ownerEntityId(), request.requestId()));
        }
        events.publish(new DataSubjectRequestFulfilled(
                request.requestId(), request.customerId(), request.ownerEntityId(), request.kind()));
        return request.requestId();
    }

    /**
     * The RetentionGuard of 27A section 6.4 as CR-27A-1 item 3 states it: every account of the
     * customer at every society, locked, CLOSED, at zero, and closed before the offline window.
     */
    private void requireEveryAccountSettledAndClosed(UUID customerId, ScopeContext scope) {
        int waitDays = config.getInt(WAIT_DAYS, scope, DEFAULT_WAIT_DAYS);
        Timestamp closedSince = Timestamp.from(clock.now().minus(Duration.ofDays(waitDays)));
        record Counts(int notClosed, int withBalance, int recentlyClosed) {}
        List<Counts> counts = jdbc.query(
                "select not_closed, with_balance, recently_closed from customers.lock_accounts_for_erasure(?, ?)",
                (rs, n) -> new Counts(rs.getInt("not_closed"), rs.getInt("with_balance"), rs.getInt("recently_closed")),
                customerId,
                closedSince);
        if (counts.isEmpty()) {
            // The function answers nothing to a caller that did not register the customer.
            throw new ProblemException("m7.privacy.not_registering_society");
        }
        Counts found = counts.get(0);
        if (found.withBalance() > 0) {
            throw new ProblemException("m7.privacy.open_balance");
        }
        if (found.notClosed() > 0) {
            throw new ProblemException("m7.privacy.account_open", Map.of("accounts", found.notClosed()));
        }
        if (found.recentlyClosed() > 0) {
            throw new ProblemException("m7.privacy.recently_closed", Map.of("waitDays", waitDays));
        }
    }

    /** The Anonymiser of 27A section 6.4: the identity and the module's own free text go, the ledger stays. */
    private void anonymise(UUID customerId) {
        Timestamp now = Timestamp.from(clock.now());
        jdbc.update(
                """
                update customers.customer
                   set display_name = ?, display_name_si = null, display_name_ta = null, nic_hash = null,
                       nic_last4 = null, nic_key_id = null, attributes = '{}'::jsonb, status = 'ANONYMISED'
                 where customer_id = ?
                """,
                ANONYMOUS_NAME,
                customerId);
        jdbc.update(
                """
                update customers.customer_phone
                   set phone = ?, reason = 'ERASURE', valid_to = coalesce(valid_to, ?)
                 where customer_id = ?
                """,
                ERASED_PHONE,
                now,
                customerId);
        jdbc.update(
                "update customers.customer_consent set withdrawn_at = ? where customer_id = ? and withdrawn_at is null",
                now,
                customerId);
        jdbc.update(
                "update customers.customer_tag set removed_at = ? where customer_id = ? and removed_at is null",
                now,
                customerId);
        // Every request of the customer, this one included (its outcome is set to ANONYMISED by the
        // caller): the free text an officer typed about the person goes with the identity.
        jdbc.update(
                "update customers.data_subject_request set notes = null, outcome = ? where customer_id = ?",
                ERASED_OUTCOME,
                customerId);
    }
}
