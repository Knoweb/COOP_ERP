package lk.coopfed.knoweb.kernel.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** DEMO-02: the application's clock moves back only for the demo loader, on its own thread. */
class HistoricalClockTest {

    private static final Instant NOW = Instant.parse("2026-09-28T06:00:00Z");
    private static final Instant PAST = Instant.parse("2026-08-01T04:00:00Z");

    private final Clock system = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void answersThePastMomentOnlyInsideTheWorkAndOnlyOnItsThread() throws Exception {
        HistoricalClock clock = new HistoricalClock(system, true);

        Instant[] seen = new Instant[2];
        boolean[] historical = new boolean[1];
        clock.run(PAST, () -> {
            seen[0] = clock.instant();
            historical[0] = HistoricalClock.isHistorical();
            seen[1] = CompletableFuture.supplyAsync(clock::instant).join();
        });

        assertThat(seen[0]).isEqualTo(PAST);
        assertThat(historical[0]).isTrue();
        assertThat(seen[1]).as("another thread keeps the real time").isEqualTo(NOW);
        assertThat(clock.instant()).isEqualTo(NOW);
        assertThat(HistoricalClock.isHistorical()).isFalse();
    }

    @Test
    void restoresTheRealTimeWhenTheWorkFails() {
        HistoricalClock clock = new HistoricalClock(system, true);

        assertThatThrownBy(() -> clock.run(PAST, () -> {
                    throw new IllegalStateException("refused");
                }))
                .hasMessage("refused");

        assertThat(clock.instant()).isEqualTo(NOW);
        assertThat(HistoricalClock.isHistorical()).isFalse();
    }

    @Test
    void refusesUnlessTheDemoContainerEnabledIt() {
        HistoricalClock clock = new HistoricalClock(system, false);

        assertThatThrownBy(() -> clock.run(PAST, () -> {})).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAMomentInTheFuture() {
        HistoricalClock clock = new HistoricalClock(system, true);

        assertThatThrownBy(() -> clock.run(NOW.plusSeconds(60), () -> {})).isInstanceOf(IllegalArgumentException.class);
    }
}
