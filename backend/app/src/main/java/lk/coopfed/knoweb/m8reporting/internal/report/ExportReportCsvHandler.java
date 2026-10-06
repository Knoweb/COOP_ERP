package lk.coopfed.knoweb.m8reporting.internal.report;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m8reporting.api.ExportReportCsv;
import lk.coopfed.knoweb.m8reporting.api.ReportExported;
import lk.coopfed.knoweb.m8reporting.query.ReportParameters;
import lk.coopfed.knoweb.m8reporting.query.ReportTable;
import lk.coopfed.knoweb.m8reporting.query.ReportingQueries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ExportReportCsv (wave 2, M8-11, decision D7; CR-28A-2 item 6): reads a report's rows as the
 * screen does and records that they left as a file. Financial data taken away (receivables,
 * ageing, invoices) leaves a trace; reading the same rows on the screen does not, because the
 * dashboard's daily traffic would bury the trail.
 *
 * <p>Guards: a caller in an OWN scope ({@code m8.export.own_required}: the audit record and the
 * event belong to the exporting entity, and the classes that write nothing, the Federation view
 * and an external grant, cannot leave one; no template of theirs holds {@code rpt.export.run});
 * then the report's own (ReportingQueries.report): the report known
 * ({@code m8.report.unknown}), its period given, in order and not too long
 * ({@code m8.report.period_required}, {@code m8.report.period_invalid},
 * {@code m8.report.period_too_long}), no more rows than {@code reporting.max_rows}
 * ({@code m8.report.too_many_rows}). Nothing is stored but the audit record. Audit
 * {@code REPORT_EXPORTED} with the report, its parameters and the row count, no row content; event
 * {@code report.exported.v1}.
 */
@Service
@CommandHandler(permission = "rpt.export.run")
class ExportReportCsvHandler implements Handles<ExportReportCsv, ReportTable> {

    static final String AUDIT_EXPORTED = "REPORT_EXPORTED";

    private final ReportingQueries queries;
    private final AuditFacade audit;
    private final EventPublisher events;

    ExportReportCsvHandler(ReportingQueries queries, AuditFacade audit, EventPublisher events) {
        this.queries = queries;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public ReportTable handle(ExportReportCsv command, ScopeContext scope) {
        if (scope.policyClass() != PolicyClass.OWN || scope.entityId() == null) {
            throw new ProblemException("m8.export.own_required");
        }
        ReportTable table = queries.report(
                command.reportId(), new ReportParameters(command.from(), command.to(), command.locationId()), scope);

        UUID exportId = Ids.next();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reportId", table.reportId());
        after.put("from", command.from() == null ? null : command.from().toString());
        after.put("to", command.to() == null ? null : command.to().toString());
        after.put("locationId", command.locationId());
        after.put("rowCount", table.rows().size());
        audit.record(AUDIT_EXPORTED, Subject.of("report_export", exportId), null, after, scope);
        events.publish(new ReportExported(
                exportId,
                table.reportId(),
                command.from(),
                command.to(),
                command.locationId(),
                table.rows().size()));
        return table;
    }
}
