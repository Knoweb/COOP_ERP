package lk.coopfed.knoweb.kernel.internal.notification;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.springframework.stereotype.Component;

/**
 * The retry sweep of 19A section 10, every two minutes: every QUEUED notification whose next
 * attempt is due gets one more attempt, in the OWN scope of its entity. Reads the due rows as
 * a federation-wide viewer, because they belong to every entity, at most
 * {@code notification.retry.batch_size} of them (the register), the earliest first; what is
 * left is due again at the next run. Each row is claimed before it is sent
 * ({@link NotificationService#attempt}), so a sweep that outlives its lock and the next sweep
 * never send the same row twice.
 */
@Component
class RetrySweepJob {

    static final String BATCH_SIZE = "notification.retry.batch_size";

    private final NotificationLog logRows;
    private final NotificationService service;
    private final SystemScope system;
    private final ConfigRegistry config;
    private final java.time.Clock clock;

    RetrySweepJob(
            NotificationLog logRows,
            NotificationService service,
            SystemScope system,
            ConfigRegistry config,
            java.time.Clock clock) {
        this.logRows = logRows;
        this.service = service;
        this.system = system;
        this.config = config;
        this.clock = clock;
    }

    @ScheduledJob(name = "notification-retry", cron = "0 */2 * * * *", lockTimeout = "PT10M", maxRuntime = "PT5M")
    public int retryDue() {
        ScopeContext viewer = SystemScope.federationView();
        List<NotificationLog.Row> due =
                system.inScope(viewer, () -> logRows.due(clock.instant(), config.getInt(BATCH_SIZE, viewer, 200)));

        int attempted = 0;
        for (NotificationLog.Row row : due) {
            if (service.attempt(
                    row.notificationId(),
                    row.channel(),
                    row.recipientHash(),
                    row.templateId(),
                    row.eventId(),
                    row.ownerEntityId())) {
                attempted++;
            }
        }
        return attempted;
    }
}
