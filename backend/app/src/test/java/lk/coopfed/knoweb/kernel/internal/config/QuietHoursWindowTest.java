package lk.coopfed.knoweb.kernel.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The register refuses quiet hours longer than fifteen hours ({@code window_max_hours}): a
 * deferral then never reaches the 24-hour age at which a notification is given up (CR-19A-12).
 */
class QuietHoursWindowTest {

    @Test
    void aWindowUpToFifteenHoursIsAccepted() {
        assertThat(ConfigValues.windowWithin("22:00-07:00", 15)).isTrue();
        assertThat(ConfigValues.windowWithin("12:00-14:00", 15)).isTrue();
        assertThat(ConfigValues.windowWithin("18:00-09:00", 15)).isTrue();
        assertThat(ConfigValues.windowWithin("", 15)).isTrue();
    }

    @Test
    void aLongerWindowOrOneWithEqualEndsIsRefused() {
        assertThat(ConfigValues.windowWithin("18:00-09:01", 15)).isFalse();
        assertThat(ConfigValues.windowWithin("00:00-23:59", 15)).isFalse();
        assertThat(ConfigValues.windowWithin("07:00-07:00", 15)).isFalse();
        assertThat(ConfigValues.windowWithin("7 to 9", 15)).isFalse();
    }
}
