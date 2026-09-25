package lk.coopfed.knoweb.kernel.internal.notification;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.springframework.stereotype.Component;

/**
 * The retry sweep of 19A section 10, every two minutes: every QUEUED notification whose next
 * attempt is due gets one more attempt, in the OWN scope of its entity, one transaction each.
 * Reads as a federation-wide viewer, because the due rows belong to every entity.
 */
@Component
class RetrySweepJob {

    private final NotificationLog logRows;
    private final NotificationService service;
    private final SystemScope system;
    private final java.time.Clock clock;

    RetrySweepJob(NotificationLog logRows, NotificationService service, SystemScope system, java.time.Clock clock) {
        this.logRows = logRows;
        this.service = service;
        this.system = system;
        this.clock = clock;
    }

    @ScheduledJob(name = "notification-retry", cron = "0 */2 * * * *", lockTimeout = "PT10M", maxRuntime = "PT5M")
    public int retryDue() {
        List<NotificationLog.Row> due =
                system.inScope(SystemScope.federationView(), () -> logRows.due(clock.instant()));

        int attempted = 0;
        for (NotificationLog.Row row : due) {
            ScopeContext owner = SystemScope.own(row.ownerEntityId(), null);
            system.inScope(owner, () -> {
                service.attempt(row.notificationId(), row.channel(), row.attempts(), owner);
                return null;
            });
            attempted++;
        }
        return attempted;
    }
}
