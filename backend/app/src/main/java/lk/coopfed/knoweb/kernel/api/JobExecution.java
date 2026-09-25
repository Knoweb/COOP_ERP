package lk.coopfed.knoweb.kernel.api;

import java.util.Optional;
import java.util.UUID;

/**
 * What a {@link ScheduledJob} method may ask of the run it is in.
 */
public interface JobExecution {

    /** The id of this run's row in {@code kernel.job_run}. */
    UUID runId();

    /** Counts what the job processed, for the run history; the return value of the method does the same. */
    void itemsProcessed(int count);

    /**
     * The scope the platform itself acts in when no request is behind the work: the OWN
     * scope of the entity {@code coop-erp.system.entity-id} names (the Federation). Empty
     * when it is not configured; a job that must write tenant rows or an audit record then
     * logs instead.
     */
    Optional<ScopeContext> systemScope();
}
