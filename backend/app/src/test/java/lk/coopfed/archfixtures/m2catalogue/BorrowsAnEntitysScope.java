package lk.coopfed.archfixtures.m2catalogue;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * Violates the job-scope rule: an ordinary method takes another entity's OWN scope from a job's
 * execution. Only a {@code @ScheduledJob} method may, for the rows its module read itself.
 */
public class BorrowsAnEntitysScope {

    public ScopeContext borrow(JobExecution execution, UUID anyEntity) {
        return execution.ownScopeOf(anyEntity);
    }

    /** Allowed: the job method itself. */
    @ScheduledJob(name = "fixture", cron = "0 0 * * * *")
    public int job(JobExecution execution) {
        execution.ownScopeOf(new UUID(0, 1));
        return 0;
    }
}
