package lk.coopfed.knoweb.kernel.internal.job;

import jakarta.annotation.PreDestroy;
import java.time.ZoneId;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * Puts every registered job on the clock, in the worker role only (17A section 4.1: the worker
 * runs the scheduled jobs). A cron job fires by its expression in the business time zone; a
 * continuous job runs again its fixed delay after the last run ended. Each firing goes
 * through {@link JobRunner}, which is where the lock, the catalogue switch and the run
 * history are.
 *
 * <p>The jobs have a thread pool of their own, one thread per registered job, and not the
 * application's {@code TaskScheduler}: a firing blocks its thread for the whole run (up to
 * {@code maxRuntime}, twice for a critical job), and on the shared single-threaded scheduler a
 * twenty-minute cut-off stalled the outbox relay's poll and every other firing (review of
 * 26 Sep). The pool is not a bean, so Spring's own {@code @Scheduled} methods keep the default.
 */
@Component
@Profile("worker")
class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final JobRegistry registry;
    private final Consumer<String> run;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final ZoneId zone;

    @Autowired
    JobScheduler(JobRegistry registry, JobRunner runner, @Value("${coop-erp.business-timezone}") String zone) {
        this(registry, runner::run, zone);
    }

    /** For a test: the firing is whatever the caller passes, no runner and no database. */
    JobScheduler(JobRegistry registry, Consumer<String> run, String zone) {
        this.registry = registry;
        this.run = run;
        this.zone = ZoneId.of(zone);
    }

    @EventListener(ApplicationReadyEvent.class)
    void scheduleAll() {
        // One thread per job: a job that blocks for its maximum runtime delays no other job.
        scheduler.setPoolSize(Math.max(1, registry.all().size()));
        scheduler.setThreadNamePrefix("job-scheduler-");
        scheduler.setDaemon(true);
        scheduler.initialize();

        for (JobDefinition job : registry.all()) {
            Runnable firing = () -> {
                try {
                    run.accept(job.name());
                } catch (RuntimeException e) {
                    log.error("Scheduled job {} could not be started", job.name(), e);
                }
            };

            if (job.continuous()) {
                scheduler.scheduleWithFixedDelay(firing, job.fixedDelay());
            } else {
                scheduler.schedule(firing, new CronTrigger(job.cron(), zone));
            }
        }

        log.info("Scheduled {} job(s) in {} on {} thread(s)", registry.all().size(), zone, scheduler.getPoolSize());
    }

    @PreDestroy
    void stop() {
        scheduler.shutdown();
    }
}
