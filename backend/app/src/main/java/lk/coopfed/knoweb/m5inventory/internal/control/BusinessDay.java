package lk.coopfed.knoweb.m5inventory.internal.control;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The business date stock is judged by (wave 2, M5-01; decided 6 October 2026:
 * {@code docs/progress/deviations/2026-10-06-wave2-expired-stock.md} (1)): the date in the business
 * time zone ({@code coop-erp.business-timezone}, Colombo) from the application's clock. A lot is
 * <b>expired</b> when its expiry date is before this date, so it is sellable through its printed
 * date. The queries take the date as a parameter and never use SQL {@code current_date}: the
 * session runs in UTC, which between 00:00 and 05:30 Colombo is still yesterday.
 */
@Component
public class BusinessDay {

    private final Clock clock;
    private final ZoneId zone;

    BusinessDay(Clock clock, @Value("${coop-erp.business-timezone}") String zone) {
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    /** Today in the business time zone. */
    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    /** The business date of a moment (a till receipt's issue time); today when it is unknown. */
    public LocalDate dateOf(Instant moment) {
        return moment == null ? today() : LocalDate.ofInstant(moment, zone);
    }

    /** Whether a lot of this expiry date is expired on the date: before it, never on it. */
    public static boolean expired(LocalDate expiryDate, LocalDate date) {
        return expiryDate != null && expiryDate.isBefore(date);
    }
}
