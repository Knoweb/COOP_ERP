package lk.coopfed.knoweb.m3pricing.internal.label;

import java.util.LinkedHashMap;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m3pricing.api.LabelJobFailed;
import lk.coopfed.knoweb.m3pricing.api.LabelJobRendered;
import lk.coopfed.knoweb.m3pricing.internal.label.LabelJobStore.Row;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records a label job's terminal outcome exactly once, after rendering has left its transaction. */
@Service
@CommandHandler(permission = "prc.label.print")
class CompleteLabelJobHandler implements Handles<CompleteLabelJob, String> {

    static final String AUDIT_RENDERED = "LABEL_JOB_RENDERED";
    static final String AUDIT_FAILED = "LABEL_JOB_FAILED";

    private final LabelJobStore jobs;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    CompleteLabelJobHandler(LabelJobStore jobs, JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public String handle(CompleteLabelJob command, ScopeContext scope) {
        if (command == null || command.jobId() == null || (!command.failed() && command.objectKey() == null)) {
            throw new ProblemException("request.invalid");
        }
        Row before =
                jobs.findForUpdate(command.jobId()).orElseThrow(() -> new ProblemException("m3.label.job_not_found"));
        if (!LabelJobStore.QUEUED.equals(before.status())) {
            throw new ProblemException("m3.label.job_not_queued");
        }
        String status = command.failed() ? LabelJobStore.FAILED : LabelJobStore.RENDERED;
        jdbc.update(
                "update pricing.label_job set status = ?, object_key = ?, refused = ?::jsonb where job_id = ?",
                status,
                command.objectKey(),
                jobs.refusedJson(command.refused()),
                command.jobId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", status);
        after.put("refusedCount", command.refused().size());
        after.put("renderedCount", command.renderedCount());
        if (command.objectKey() != null) {
            after.put("objectKey", command.objectKey());
        }
        audit.record(
                command.failed() ? AUDIT_FAILED : AUDIT_RENDERED,
                Subject.of("label_job", command.jobId()),
                Map.of("status", LabelJobStore.QUEUED),
                after,
                scope);
        if (command.failed()) {
            events.publish(new LabelJobFailed(command.jobId(), before.locationId()));
        } else {
            events.publish(new LabelJobRendered(command.jobId(), before.locationId(), command.renderedCount()));
        }
        return status;
    }
}
