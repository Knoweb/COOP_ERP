package lk.coopfed.knoweb.m7customers.internal.privacy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m7customers.api.AccessExportDownloaded;
import lk.coopfed.knoweb.m7customers.api.DownloadAccessExport;
import lk.coopfed.knoweb.m7customers.api.RecordDataSubjectRequest;
import lk.coopfed.knoweb.m7customers.internal.customer.CustomerGuards;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DownloadAccessExport (wave 2, M7CR-11; CR-27A-1 item 4; doc 27 section 9.3: "every read of a
 * customer record outside the sales path is audited"). Guards, in order: the society's OWN scope;
 * the request of this society ({@code m7.privacy.not_found}), of kind ACCESS ({@code
 * m7.privacy.not_access}), FULFILLED ({@code m7.privacy.not_fulfilled}); the customer not
 * anonymised since ({@code m7.customer.anonymised}); the caller is the society's responsible
 * officer ({@code m7.privacy.no_officer}, {@code m7.privacy.officer_only}); the second factor is
 * the permission's ({@code cus.privacy.fulfil} requires MFA).
 *
 * <p>The export is rebuilt from the rows as they are now ({@link PrivacyExporter}); {@code
 * export_sha256} on the request is the hash at fulfilment, and the audit says whether the two
 * still agree. No row changes. Audit DSAR_EXPORT_DOWNLOADED with the request id and {@code
 * matchesFulfilment} (never the export's content); event dsar.export_downloaded.v1. Answers the
 * export, which the controller hands over.
 */
@Service
@CommandHandler(permission = "cus.privacy.fulfil")
class DownloadAccessExportHandler implements Handles<DownloadAccessExport, Map<String, Object>> {

    static final String AUDIT_DOWNLOADED = "DSAR_EXPORT_DOWNLOADED";

    private final JdbcTemplate jdbc;
    private final PartyQueries parties;
    private final PrivacyExporter exporter;
    private final AuditFacade audit;
    private final EventPublisher events;

    DownloadAccessExportHandler(
            JdbcTemplate jdbc,
            PartyQueries parties,
            PrivacyExporter exporter,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.parties = parties;
        this.exporter = exporter;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Map<String, Object> handle(DownloadAccessExport command, ScopeContext scope) {
        if (command == null || command.requestId() == null) {
            throw new ProblemException("request.invalid");
        }
        CustomerGuards.requireOwnScope(scope);
        PrivacyGuards.Request request = PrivacyGuards.lockRequest(jdbc, command.requestId());
        if (!RecordDataSubjectRequest.ACCESS.equals(request.kind())) {
            throw new ProblemException("m7.privacy.not_access", Map.of("kind", request.kind()));
        }
        if (!"FULFILLED".equals(request.status())) {
            throw new ProblemException("m7.privacy.not_fulfilled", Map.of("status", request.status()));
        }
        if ("ANONYMISED".equals(CustomerGuards.customerStatus(jdbc, request.customerId()))) {
            throw new ProblemException("m7.customer.anonymised");
        }
        PrivacyGuards.requireResponsibleOfficer(parties, scope);

        Map<String, Object> export = exporter.export(request.customerId());
        List<String> recorded = jdbc.queryForList(
                "select export_sha256 from customers.data_subject_request where request_id = ?",
                String.class,
                request.requestId());
        boolean matches = !recorded.isEmpty()
                && recorded.get(0) != null
                && recorded.get(0).equals(exporter.sha256(export));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("customerId", request.customerId());
        after.put("matchesFulfilment", matches);
        audit.record(AUDIT_DOWNLOADED, Subject.of("data_subject_request", request.requestId()), null, after, scope);
        events.publish(new AccessExportDownloaded(request.requestId(), request.customerId(), request.ownerEntityId()));
        return export;
    }
}
