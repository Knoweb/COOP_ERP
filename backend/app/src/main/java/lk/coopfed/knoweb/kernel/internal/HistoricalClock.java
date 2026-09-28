package lk.coopfed.knoweb.kernel.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.HistoricalTime;

/**
 * The application's clock: the system clock, except on a thread inside {@link #at}, where it
 * answers the moment given (DEMO-02, the demo loader only). {@code LocationBusinessDates} asks
 * {@link #isHistorical()} so that a location's business date follows the same moment instead of
 * its day-close state.
 */
public final class HistoricalClock extends Clock implements HistoricalTime {

    private static final ThreadLocal<Instant> MOMENT = new ThreadLocal<>();

    private final Clock system;
    private final boolean enabled;

    public HistoricalClock(Clock system, boolean enabled) {
        this.system = system;
        this.enabled = enabled;
    }

    /** True on a thread running the work of {@link #at}: the time is not the real time. */
    public static boolean isHistorical() {
        return MOMENT.get() != null;
    }

    @Override
    public <T> T at(Instant moment, Supplier<T> work) {
        Objects.requireNonNull(moment, "moment");
        if (!enabled) {
            throw new IllegalStateException(
                    "HistoricalTime is for the demo loader only (coop-erp.demo.historical-time is false)");
        }
        if (moment.isAfter(system.instant())) {
            throw new IllegalArgumentException("HistoricalTime runs work in the past, not at " + moment);
        }
        Instant outer = MOMENT.get();
        MOMENT.set(moment);
        try {
            return work.get();
        } finally {
            if (outer == null) {
                MOMENT.remove();
            } else {
                MOMENT.set(outer);
            }
        }
    }

    @Override
    public Instant instant() {
        Instant moment = MOMENT.get();
        return moment != null ? moment : system.instant();
    }

    @Override
    public ZoneId getZone() {
        return system.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new HistoricalClock(system.withZone(zone), enabled);
    }
}
