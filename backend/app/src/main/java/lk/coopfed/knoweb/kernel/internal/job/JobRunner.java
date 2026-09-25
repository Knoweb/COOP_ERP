package lk.coopfed.knoweb.kernel.internal.job;

import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs one job once (19A section 12): skips it when the catalogue says disabled or another
 * instance holds its lock; records the run; interrupts it past its maximum runtime and marks
 * the run TIMEOUT with an ALERT; marks a failure FAILED with a REVIEW record and, for a
 * critical job, tries once more at once. The lock outlives the run ({@code lockTimeout}), so
 * a run that dies with its instance is not overlapped before the lock expires.
 *
 * <p>The audit records need a scope; they are written in the system scope when
 * {@code coop-erp.system.entity-id} is set and only logged otherwise.
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    static final String AUDIT_TIMED_OUT = "JOB_TIMED_OUT";
    static final String AUDIT_FAILED = "JOB_FAILED";

    public enum Outcome {
        SUCCESS,
        FAILED,
        TIMEOUT,
        SKIPPED_DISABLED,
        SKIPPED_LOCKED,
        UNKNOWN_JOB
    }

    private final JobRegistry registry;
    private final LockProvider locks;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final SystemScope system;
    private final Clock clock;
    private final String instance;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "scheduled-job");
        thread.setDaemon(true);
        return thread;
    });

    public JobRunner(
            JobRegistry registry,
            LockProvider locks,
            JdbcTemplate jdbc,
            AuditFacade audit,
            SystemScope system,
            Clock clock) {
        this.registry = registry;
        this.locks = locks;
        this.jdbc = jdbc;
        this.audit = audit;
        this.system = system;
        this.clock = clock;
        this.instance = instanceName();
    }

    /** Runs the named job now, on this instance, if it is enabled and nobody else is running it. */
    public Outcome run(String name) {
        Optional<JobDefinition> found = registry.find(name);

        if (found.isEmpty()) {
            log.warn("Scheduled job {} is not registered on this instance", name);
            return Outcome.UNKNOWN_JOB;
        }

        JobDefinition job = found.get();

        if (!enabled(name)) {
            log.debug("Scheduled job {} is disabled in kernel.scheduled_job", name);
            return Outcome.SKIPPED_DISABLED;
        }

        Optional<SimpleLock> lock = locks.lock(
                new LockConfiguration(clock.instant(), "job:" + name, job.lockTimeout(), java.time.Duration.ZERO));

        if (lock.isEmpty()) {
            log.debug("Scheduled job {} is running on another instance", name);
            return Outcome.SKIPPED_LOCKED;
        }

        try {
            Outcome outcome = runOnce(job);

            if (outcome == Outcome.FAILED && job.critical()) {
                log.warn("Critical job {} failed; retrying once", name);
                outcome = runOnce(job);
            }

            return outcome;
        } finally {
            lock.get().unlock();
        }
    }

    private Outcome runOnce(JobDefinition job) {
        UUID runId = Ids.next();
        Instant startedAt = clock.instant();

        jdbc.update(
                "insert into kernel.job_run (run_id, name, started_at, instance) values (?, ?, ?, ?)",
                runId,
                job.name(),
                Timestamp.from(startedAt),
                instance);

        Execution execution = new Execution(runId, system.own());

        Future<Integer> future = executor.submit(() -> invoke(job, execution));

        try {
            Integer returned = future.get(job.maxRuntime().toMillis(), TimeUnit.MILLISECONDS);
            int items = returned != null ? returned : execution.items.get();
            finish(runId, Outcome.SUCCESS, items, null);
            log.info("Scheduled job {} finished: {} item(s)", job.name(), items);
            return Outcome.SUCCESS;

        } catch (TimeoutException timeout) {
            future.cancel(true);
            String error = "exceeded max runtime " + job.maxRuntime();
            finish(runId, Outcome.TIMEOUT, execution.items.get(), error);
            log.error("ALERT scheduled job {} {}", job.name(), error);
            record(AUDIT_TIMED_OUT, job, runId, error);
            return Outcome.TIMEOUT;

        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            String error = errorText(cause);
            finish(runId, Outcome.FAILED, execution.items.get(), error);
            log.error("Scheduled job {} failed: {}", job.name(), error, cause);
            record(AUDIT_FAILED, job, runId, error);
            return Outcome.FAILED;

        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            finish(runId, Outcome.FAILED, execution.items.get(), "interrupted");
            return Outcome.FAILED;
        }
    }

    private static Integer invoke(JobDefinition job, Execution execution) throws Exception {
        try {
            Object result = job.method().getParameterCount() == 0
                    ? job.method().invoke(job.bean())
                    : job.method().invoke(job.bean(), execution);
            return result instanceof Integer count ? count : null;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private boolean enabled(String name) {
        Boolean enabled = jdbc
                .query(
                        "select enabled from kernel.scheduled_job where name = ?",
                        (rs, rowNum) -> rs.getBoolean("enabled"),
                        name)
                .stream()
                .findFirst()
                .orElse(Boolean.TRUE);
        return Boolean.TRUE.equals(enabled);
    }

    private void finish(UUID runId, Outcome outcome, int items, String error) {
        jdbc.update(
                "update kernel.job_run set finished_at = ?, outcome = ?, items_processed = ?, error = ? where run_id = ?",
                Timestamp.from(clock.instant()),
                outcome.name(),
                items,
                error,
                runId);
    }

    private void record(String eventType, JobDefinition job, UUID runId, String error) {
        Optional<ScopeContext> scope = system.own();

        if (scope.isEmpty()) {
            log.warn("{} for job {} not audited: coop-erp.system.entity-id is not set", eventType, job.name());
            return;
        }

        try {
            system.inScope(scope.get(), () -> {
                audit.record(
                        eventType,
                        Subject.of("job_run", runId),
                        null,
                        Map.of("job", job.name(), "instance", instance, "error", error),
                        scope.get(),
                        error);
                return null;
            });
        } catch (RuntimeException e) {
            log.error("Could not write the {} audit record for job {}", eventType, job.name(), e);
        }
    }

    private static String errorText(Throwable failure) {
        String text = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    private static String instanceName() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        return host + ":" + ProcessHandle.current().pid();
    }

    /** What the job sees of its run. */
    private static final class Execution implements JobExecution {

        private final UUID runId;
        private final Optional<ScopeContext> systemScope;
        private final AtomicInteger items = new AtomicInteger();

        Execution(UUID runId, Optional<ScopeContext> systemScope) {
            this.runId = runId;
            this.systemScope = systemScope;
        }

        @Override
        public UUID runId() {
            return runId;
        }

        @Override
        public void itemsProcessed(int count) {
            items.addAndGet(count);
        }

        @Override
        public Optional<ScopeContext> systemScope() {
            return systemScope;
        }
    }
}
