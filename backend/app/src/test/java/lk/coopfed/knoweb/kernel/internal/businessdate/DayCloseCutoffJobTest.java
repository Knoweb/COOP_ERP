package lk.coopfed.knoweb.kernel.internal.businessdate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * The cut-off fires every fifteen minutes and acts only at or after the cut-off time in the
 * business time zone, so a missed firing is made up by the next one and never runs before the
 * shops' day has ended (review wave 3, KRN-18).
 */
class DayCloseCutoffJobTest {

    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");
    private static final LocalTime CUTOFF = LocalTime.of(2, 30);

    @Test
    void aFiringBeforeTheCutoffDoesNothingAndEveryFiringAfterItCloses() {
        // 02:29 and 02:30 in Colombo (UTC+05:30) are 20:59 and 21:00 UTC the evening before.
        assertThat(DayCloseCutoffJob.pastCutoff(Instant.parse("2026-10-08T20:59:00Z"), COLOMBO, CUTOFF))
                .isFalse();
        assertThat(DayCloseCutoffJob.pastCutoff(Instant.parse("2026-10-08T21:00:00Z"), COLOMBO, CUTOFF))
                .isTrue();
        // A worker that was down at 02:30 closes the day at its next firing, later that day.
        assertThat(DayCloseCutoffJob.pastCutoff(Instant.parse("2026-10-09T06:15:00Z"), COLOMBO, CUTOFF))
                .isTrue();
        // Just after midnight the shops may still be trading on yesterday's date.
        assertThat(DayCloseCutoffJob.pastCutoff(Instant.parse("2026-10-08T18:45:00Z"), COLOMBO, CUTOFF))
                .isFalse();
    }
}
