package lk.coopfed.archfixtures.m2catalogue;

import java.util.UUID;
import lk.coopfed.archfixtures.kernel.api.MintsAScope;
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

    /** Violates it too (CR-19A-14): the federation-wide view outside the job method. */
    public ScopeContext peek(JobExecution execution) {
        return execution.federationView();
    }

    /** Violates it too (CR-19A-14): a kernel API that mints any entity's scope, from anywhere. */
    public ScopeContext mint(MintsAScope minter, UUID anyEntity) {
        return minter.own(anyEntity);
    }

    /** Allowed: the job method itself. */
    @ScheduledJob(name = "fixture", cron = "0 0 * * * *")
    public int job(JobExecution execution) {
        execution.ownScopeOf(new UUID(0, 1));
        execution.federationView();
        return 0;
    }
}
