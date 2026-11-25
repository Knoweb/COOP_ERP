package lk.coopfed.knoweb.m3pricing.internal.label;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.internal.label.LabelJobStore.Row;
import lk.coopfed.knoweb.m3pricing.internal.label.LabelRenderer.Prepared;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Renders queued label jobs on the worker role, one owner-scoped transaction at each boundary. */
@Component
class LabelJobWorker {

    private static final Logger log = LoggerFactory.getLogger(LabelJobWorker.class);

    private final LabelJobStore jobs;
    private final LabelRenderer labels;
    private final CompleteLabelJobHandler complete;
    private final Clock clock;
    private final ZoneId businessZone;

    LabelJobWorker(
            LabelJobStore jobs,
            LabelRenderer labels,
            CompleteLabelJobHandler complete,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone) {
        this.jobs = jobs;
        this.labels = labels;
        this.complete = complete;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
    }

    @ScheduledJob(
            name = "pricing-label-render",
            cron = "${coop-erp.m3.label-render-cron:*/10 * * * * *}",
            lockTimeout = "PT15M",
            maxRuntime = "PT10M")
    public int renderQueued(JobExecution execution) {
        List<Row> queued = jobs.queued(execution.federationView());
        LocalDate printDate = LocalDate.ofInstant(clock.instant(), businessZone);
        int completed = 0;
        for (Row job : queued) {
            ScopeContext owner = execution.ownScopeOf(job.ownerEntityId());
            if (process(job, printDate, owner)) {
                completed++;
            }
        }
        return completed;
    }

    boolean process(Row job, LocalDate printDate, ScopeContext owner) {
        Prepared prepared = null;
        String objectKey;
        try {
            prepared = labels.prepare(job, printDate, owner);
            objectKey = labels.render(prepared, owner);
        } catch (ProblemException failed) {
            log.warn("Label job {} failed with {}", job.jobId(), failed.messageId());
            return fail(job, prepared == null ? List.of() : prepared.refused(), owner);
        } catch (RuntimeException failed) {
            log.warn("Label job {} failed", job.jobId(), failed);
            return fail(job, prepared == null ? List.of() : prepared.refused(), owner);
        }

        try {
            complete.handle(
                    CompleteLabelJob.rendered(job.jobId(), objectKey, prepared.refused(), prepared.renderedCount()),
                    owner);
            return true;
        } catch (RuntimeException unsettled) {
            // The PDF exists but the row is still QUEUED; the next run retries rather than
            // falsely recording a render failure.
            log.error("Rendered label job {} could not be completed", job.jobId(), unsettled);
            return false;
        }
    }

    private boolean fail(Row job, List<lk.coopfed.knoweb.m3pricing.query.LabelRefusal> refused, ScopeContext owner) {
        try {
            complete.handle(CompleteLabelJob.failed(job.jobId(), refused), owner);
            return true;
        } catch (RuntimeException unsettled) {
            log.error("Failed label job {} could not be completed", job.jobId(), unsettled);
            return false;
        }
    }
}
