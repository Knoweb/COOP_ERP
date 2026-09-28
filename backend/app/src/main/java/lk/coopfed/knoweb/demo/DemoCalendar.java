package lk.coopfed.knoweb.demo;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.HistoricalTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * When each part of the demo happened (DEMO-02): the set-up (catalogue, price lists,
 * relationships, opening stock, the town shop's transfer) {@link #SETUP_DAYS_AGO} days ago, the
 * trading history spread over the eight weeks since. The loader issues every command through the
 * ordinary handlers inside {@link HistoricalTime}, so the documents carry those business dates
 * and take their numbers in date order; nothing is faked in the database.
 */
@Component
class DemoCalendar {

    /** The demo federation opened for business this many days before the load. */
    static final int SETUP_DAYS_AGO = 60;

    /** The first order of every relationship was placed this many days before the load. */
    static final int HISTORY_DAYS = 55;

    private final HistoricalTime time;
    private final Clock clock;
    private final ZoneId zone;

    DemoCalendar(HistoricalTime time, Clock clock, @Value("${coop-erp.business-timezone}") String zone) {
        this.time = time;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    /**
     * Runs the work at that day and time of the business time zone, or now when that is still to
     * come (an order of today, loaded before nine in the morning).
     */
    <T> T at(LocalDate day, LocalTime at, Supplier<T> work) {
        Instant now = clock.instant();
        Instant moment = day.atTime(at).atZone(zone).toInstant();
        return time.at(moment.isAfter(now) ? now : moment, work);
    }

    void run(LocalDate day, LocalTime at, Runnable work) {
        at(day, at, () -> {
            work.run();
            return null;
        });
    }
}
