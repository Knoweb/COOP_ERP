package lk.coopfed.knoweb.kernel.internal.stub;

import lk.coopfed.knoweb.kernel.api.BusinessDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * 17A stub: the business date is the calendar date in the business time zone, the same for
 * every location. 19A K-13 replaces it with the day-close state of the location.
 *
 * <p>It reads the kernel clock, so a test with a fixed clock gets a fixed business date, and
 * the zone comes from {@code coop-erp.business-timezone}: one place, not a constant here.
 */
@Component
public class CalendarBusinessDate implements BusinessDate {

    private final Clock clock;
    private final ZoneId zone;

    public CalendarBusinessDate(
            Clock clock,
            @Value("${coop-erp.business-timezone}") String zone) {
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    @Override
    public LocalDate current(UUID locationId) {
        Objects.requireNonNull(locationId, "a business date belongs to a location");
        return LocalDate.now(clock.withZone(zone));
    }
}
