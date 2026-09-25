package lk.coopfed.knoweb.kernel.internal.job;

import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * Puts every registered job on the clock, in the worker role only (17A section 4.1: the worker
 * runs the scheduled jobs). A cron job fires by its expression in the business time zone; a
 * continuous job runs again its fixed delay after the last run ended. Each firing goes
 * through {@link JobRunner}, which is where the lock, the catalogue switch and the run
 * history are.
 */
@Component
@Profile("worker")
class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final JobRegistry registry;
    private final JobRunner runner;
    private final TaskScheduler scheduler;
    private final ZoneId zone;

    JobScheduler(
            JobRegistry registry,
            JobRunner runner,
            TaskScheduler scheduler,
            @Value("${coop-erp.business-timezone}") String zone) {
        this.registry = registry;
        this.runner = runner;
        this.scheduler = scheduler;
        this.zone = ZoneId.of(zone);
    }

    @EventListener(ApplicationReadyEvent.class)
    void scheduleAll() {
        for (JobDefinition job : registry.all()) {
            Runnable firing = () -> {
                try {
                    runner.run(job.name());
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

        log.info("Scheduled {} job(s) in {}", registry.all().size(), zone);
    }
}
