package lk.coopfed.knoweb.kernel.internal.businessdate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The cut-off of 19A section 12 ("or at the location's configured cut-off"): a location whose
 * last session did not close, or whose till never said so, still gets its day closed. Every
 * location whose business date is behind today is closed in the OWN scope of its entity, one
 * transaction each, so that one location's failure does not hold the others.
 *
 * <p>Before closing, every location of M1's register that has no row here gets one on today's
 * calendar date (a location registered before {@link LocationRegisteredListener} existed):
 * from the next night the cut-off sees it like any other (review of 26 Sep).
 *
 * <p>The cut-off is one time for the fleet ({@code coop-erp.business-date.cutoff-time}, 02:30 in
 * the business time zone by default); the register's {@code business_date.cutoff_time}
 * (LOCATION scope) is not read yet, a deviation from 19A section 12 recorded in
 * docs/PROGRESS.md.
 *
 * <p>Why the job fires every fifteen minutes ({@code coop-erp.business-date.cutoff-cron}) and not
 * once at the cut-off: a single firing that was missed (the worker restarting at 02:30, a
 * timeout) was never run again, so every location kept yesterday's date for a whole day and the
 * next close skipped a date (review wave 3, KRN-18). Each firing at or after the cut-off closes
 * whatever is still behind today, which is nothing once a firing has succeeded (one close per
 * day); a firing before the cut-off does nothing, as the shops may still be trading on
 * yesterday's date.
 */
@Component
class DayCloseCutoffJob {

    private static final Logger log = LoggerFactory.getLogger(DayCloseCutoffJob.class);

    private final LocationBusinessDates dates;
    private final SystemScope system;
    private final Clock clock;
    private final ZoneId zone;
    private final LocalTime cutoff;

    DayCloseCutoffJob(
            LocationBusinessDates dates,
            SystemScope system,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String zone,
            @Value("${coop-erp.business-date.cutoff-time:02:30}") String cutoff) {
        this.dates = dates;
        this.system = system;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
        this.cutoff = LocalTime.parse(cutoff);
    }

    @ScheduledJob(
            name = "day-close-cutoff",
            cron = "${coop-erp.business-date.cutoff-cron:0 0/15 * * * *}",
            lockTimeout = "PT30M",
            maxRuntime = "PT20M")
    public int cutOff() {
        if (!pastCutoff(clock.instant(), zone, cutoff)) {
            return 0;
        }
        return closeOverdueDays();
    }

    /** Whether the local time of {@code now} in {@code zone} is at or after {@code cutoff}. */
    static boolean pastCutoff(Instant now, ZoneId zone, LocalTime cutoff) {
        return !LocalTime.ofInstant(now, zone).isBefore(cutoff);
    }

    /** Closes every location behind today, whatever the time; the schedule calls it after the cut-off. */
    public int closeOverdueDays() {
        List<LocationBusinessDates.State> unregistered =
                system.inScope(SystemScope.federationView(), dates::withoutARow);

        for (LocationBusinessDates.State state : unregistered) {
            try {
                system.inScope(SystemScope.own(state.ownerEntityId(), null), () -> {
                    dates.register(state.locationId(), state.ownerEntityId());
                    return null;
                });
            } catch (RuntimeException e) {
                log.error("Business-date row could not be created for location {}", state.locationId(), e);
            }
        }

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
