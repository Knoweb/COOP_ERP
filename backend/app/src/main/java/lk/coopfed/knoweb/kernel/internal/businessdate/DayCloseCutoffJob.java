package lk.coopfed.knoweb.kernel.internal.businessdate;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The cut-off of 19A section 12 ("or at the location's configured cut-off"): a location whose
 * last session did not close, or whose till never said so, still gets its day closed. Every
 * location whose business date is behind today is closed in the OWN scope of its entity, one
 * transaction each, so that one location's failure does not hold the others.
 *
 * <p>The cut-off is one time for the fleet ({@code coop-erp.business-date.cutoff-cron},
 * 02:30 in the business time zone by default); a per-location cut-off is a configuration item
 * for K-11 to carry when a location asks for one.
 */
@Component
class DayCloseCutoffJob {

    private static final Logger log = LoggerFactory.getLogger(DayCloseCutoffJob.class);

    private final LocationBusinessDates dates;
    private final SystemScope system;

    DayCloseCutoffJob(LocationBusinessDates dates, SystemScope system) {
        this.dates = dates;
        this.system = system;
    }

    @ScheduledJob(
            name = "day-close-cutoff",
            cron = "${coop-erp.business-date.cutoff-cron:0 30 2 * * *}",
            lockTimeout = "PT30M",
            maxRuntime = "PT20M")
    public int closeOverdueDays() {
        List<LocationBusinessDates.State> overdue = system.inScope(SystemScope.federationView(), dates::behindToday);

        int closed = 0;

        for (LocationBusinessDates.State state : overdue) {
            try {
                system.inScope(
                        SystemScope.own(state.ownerEntityId(), null),
                        () -> dates.close(state.locationId(), SystemScope.own(state.ownerEntityId(), null)));
                closed++;
            } catch (RuntimeException e) {
                log.error("Cut-off day close failed for location {}", state.locationId(), e);
            }
        }

        return closed;
    }
}
