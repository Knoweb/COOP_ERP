package lk.coopfed.knoweb.kernel.internal;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one clock of the application (19A section 13: "an injectable Clock (UTC) everywhere;
 * tests use a fixed clock").
 *
 * <p>Code that needs the time injects {@link Clock} and calls {@code clock.instant()}. It never
 * calls {@code Instant.now()}, {@code LocalDate.now()} or {@code System.currentTimeMillis()}:
 * those cannot be fixed in a test, and the zone-less ones follow whatever zone the server
 * happens to run in. An architecture rule fails the build for a business module that does.
 * For a date, ask {@code BusinessDate}: a business date is not the calendar date.
 *
 * <p>The clock is the system's UTC clock. Only the demo loader moves it back, on its own thread,
 * through {@link lk.coopfed.knoweb.kernel.api.HistoricalTime}, and only in the container where
 * {@code coop-erp.demo.historical-time} is true (DEMO-02, {@link HistoricalClock}).
 *
 * <p>This is not a stub: 19A keeps it as it is and adds the per-device clock offsets.
 */
@Configuration
public class KernelClockConfig {

    @Bean
    public HistoricalClock clock(@Value("${coop-erp.demo.historical-time:false}") boolean historicalTime) {
        return new HistoricalClock(Clock.systemUTC(), historicalTime);
    }
}
