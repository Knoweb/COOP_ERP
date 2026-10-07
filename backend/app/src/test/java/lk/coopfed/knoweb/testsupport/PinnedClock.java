package lk.coopfed.knoweb.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A fixed application clock for a test class that keeps "today" in a field (M3-09, wave 2): import
 * it with {@code @Import(PinnedClock.class)} and read {@link #TODAY} instead of {@code
 * LocalDate.now(...)}. The handlers then take the same date from the clock as the test does, so a
 * run across midnight in Asia/Colombo cannot make the two disagree.
 *
 * <p>The moment is the last tenth of a second of the latest business day that has ended,
 * 23:59:59.9 in Colombo: the edge a real clock would cross, and in the past, so a second factor
 * presented "now" by a test's scope is always fresh against it. The clock does not tick.
 *
 * <p>Only beans that ask for a {@link Clock} get this one ({@code @Primary}); the kernel's
 * {@code HistoricalClock} stays as it is for the code that asks for it by its own type. Token
 * expiry is checked against the system clock by Spring Security, not by this bean.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PinnedClock {

    public static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

    /** 23:59:59.9 Asia/Colombo on the day before the current Colombo date. */
    public static final Instant INSTANT = LocalDate.ofInstant(Clock.systemUTC().instant(), COLOMBO)
            .atStartOfDay(COLOMBO)
            .toInstant()
            .minusMillis(100);

    /** The business date the pinned clock reads in Asia/Colombo. */
    public static final LocalDate TODAY = LocalDate.ofInstant(INSTANT, COLOMBO);

    @Bean
    @Primary
    Clock pinnedClock() {
        return Clock.fixed(INSTANT, ZoneOffset.UTC);
    }
}
