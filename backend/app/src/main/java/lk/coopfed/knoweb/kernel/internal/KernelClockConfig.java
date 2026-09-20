package lk.coopfed.knoweb.kernel.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

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
 * <p>This is not a stub: 19A keeps it as it is and adds the per-device clock offsets.
 */
@Configuration
public class KernelClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
