package lk.coopfed.knoweb.m9integration.internal.journal;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** ACC-03: daily review of unacknowledged journal exports. */
@Component
public class JournalExportOverdueJob {

    public static final String AUDIT_UNACKNOWLEDGED = "JOURNAL_EXPORT_UNACKNOWLEDGED";

    private static final Logger log = LoggerFactory.getLogger(JournalExportOverdueJob.class);

    private final JournalExportOverdueStore store;
    private final JournalExportOverdueReporter reporter;

    public JournalExportOverdueJob(JournalExportOverdueStore store, JournalExportOverdueReporter reporter) {
        this.store = store;
        this.reporter = reporter;
    }

    @ScheduledJob(
            name = "journal-unacknowledged-review",
            cron = "${coop-erp.m9.journal-unacknowledged-review-cron:0 45 2 * * *}",
            lockTimeout = "PT15M",
            maxRuntime = "PT10M")
    public int review(JobExecution execution) {
        List<JournalExportOverdueStore.Candidate> candidates = store.findCandidates(execution.federationView());

        int reported = 0;

        for (JournalExportOverdueStore.Candidate candidate : candidates) {
            ScopeContext ownerScope = execution.ownScopeOf(candidate.ownerEntityId());

            try {
                if (reporter.report(candidate, ownerScope)) {
                    reported++;
                }
            } catch (RuntimeException e) {
                log.error("Could not review journal export {}", candidate.exportId(), e);
            }
        }

        execution.itemsProcessed(reported);
        return reported;
    }
}
