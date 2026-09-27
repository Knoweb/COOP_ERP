package lk.coopfed.knoweb.m8reporting.internal.report;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m8reporting.api.ReportRunCompleted;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CompleteReportRun: the worker writes a run's outcome once (28A section 7, "store; run READY;
 * publish"). Run by {@link ReportRunWorker} in the OWN scope of the run's entity, with no user,
 * as M5's GRN receipt is run by its consumer: the permission is the requester's, checked when the
 * run was requested.
 *
 * <p>Guards: the run visible to the scope ({@code m8.run.not_found}); still REQUESTED
 * ({@code m8.run.not_open}: a redelivered request does not print twice). Audit
 * {@code REPORT_RUN_COMPLETED}; event {@code report_run.completed.v1}.
 */
@Service
@CommandHandler(permission = "rpt.export.run")
class CompleteReportRunHandler implements Handles<CompleteReportRun, String> {

    static final String AUDIT_COMPLETED = "REPORT_RUN_COMPLETED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    CompleteReportRunHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public String handle(CompleteReportRun command, ScopeContext scope) {
        Map<String, Object> run = jdbc
                .queryForList(
                        "select report_id, status from reporting.report_run where run_id = ? for update",
                        command.runId())
                .stream()
                .findFirst()
                .orElseThrow(() ->
                        new ProblemException("m8.run.not_found", Map.of("runId", String.valueOf(command.runId()))));
        if (!"REQUESTED".equals(run.get("status"))) {
            throw new ProblemException("m8.run.not_open");
        }
        String status = command.errorCode() == null ? "READY" : "FAILED";

        jdbc.update(
                "update reporting.report_run set status = ?, object_key = ?, error_code = ?, completed_at = ?"
                        + " where run_id = ?",
                status,
                command.objectKey(),
                command.errorCode(),
                Timestamp.from(clock.instant()),
                command.runId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", status);
        if (command.objectKey() != null) {
            after.put("objectKey", command.objectKey());
        }
        if (command.errorCode() != null) {
            after.put("errorCode", command.errorCode());
        }
        audit.record(
                AUDIT_COMPLETED,
                Subject.of("report_run", command.runId()),
                Map.of("status", "REQUESTED"),
                after,
                scope);
        String reportId = (String) run.get("report_id");
        events.publish(
                new ReportRunCompleted(command.runId(), reportId, status, command.objectKey(), command.errorCode()));
        return status;
    }
}
