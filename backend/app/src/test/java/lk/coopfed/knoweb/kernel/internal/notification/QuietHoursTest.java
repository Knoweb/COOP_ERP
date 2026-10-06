package lk.coopfed.knoweb.kernel.internal.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Quiet hours defer to the end of the window in the business zone (CR-19A-12), today or
 * tomorrow. Fixed instants in Asia/Colombo: the test never depends on the clock.
 */
class QuietHoursTest {

    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

    private static Instant colombo(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 10, day, hour, minute).atZone(COLOMBO).toInstant();
    }

    @Test
    void anEveningInsideAWindowThatWrapsMidnightWaitsForTomorrowMorning() {
        assertThat(NotificationSuppression.quietUntil("22:00-07:00", colombo(6, 23, 0), COLOMBO))
                .contains(colombo(7, 7, 0));
        assertThat(NotificationSuppression.quietUntil("22:00-07:00", colombo(6, 22, 0), COLOMBO))
                .contains(colombo(7, 7, 0));
    }

    @Test
    void anEarlyMorningInsideTheWindowWaitsForThisMorning() {
        assertThat(NotificationSuppression.quietUntil("22:00-07:00", colombo(7, 3, 15), COLOMBO))
                .contains(colombo(7, 7, 0));
    }

    @Test
    void outsideTheWindowNothingWaits() {
        assertThat(NotificationSuppression.quietUntil("22:00-07:00", colombo(7, 7, 0), COLOMBO))
                .isEmpty();
        assertThat(NotificationSuppression.quietUntil("22:00-07:00", colombo(7, 21, 59), COLOMBO))
                .isEmpty();
    }

    @Test
    void aWindowInsideOneDayEndsTheSameDay() {
        assertThat(NotificationSuppression.quietUntil("12:00-14:00", colombo(6, 13, 0), COLOMBO))
                .contains(colombo(6, 14, 0));
        assertThat(NotificationSuppression.quietUntil("12:00-14:00", colombo(6, 14, 0), COLOMBO))
                .isEmpty();
    }

    @Test
    void noWindowAnUnreadableOneOrAnEmptyOneIsNoQuietHours() {
        Instant late = colombo(6, 23, 0);
        assertThat(NotificationSuppression.quietUntil("", late, COLOMBO)).isEmpty();
        assertThat(NotificationSuppression.quietUntil(null, late, COLOMBO)).isEmpty();
        assertThat(NotificationSuppression.quietUntil("late evening", late, COLOMBO))
                .isEqualTo(Optional.empty());
        assertThat(NotificationSuppression.quietUntil("22:00-07:00-09:00", late, COLOMBO))
                .isEmpty();
        assertThat(NotificationSuppression.quietUntil("22:00-22:00", late, COLOMBO))
                .isEmpty();
    }
}
