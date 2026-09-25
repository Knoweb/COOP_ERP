package lk.coopfed.knoweb.m1party.internal.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AppVersionTest {

    @Test
    void releasesCompareNumberByNumber() {
        assertThat(AppVersion.isAtLeast("1.10", "1.9")).isTrue();
        assertThat(AppVersion.isAtLeast("1.4.2", "1.4.10")).isFalse();
        assertThat(AppVersion.isAtLeast("2", "1.99.99")).isTrue();
        assertThat(AppVersion.isAtLeast("1.2.9", "1.3")).isFalse();
    }

    @Test
    void aMissingPartCountsAsZero() {
        assertThat(AppVersion.compare("1.4", "1.4.0")).isZero();
        assertThat(AppVersion.isAtLeast("1.4.0", "0")).isTrue();
    }

    @Test
    void onlyDotSeparatedNumbersAreReleases() {
        assertThat(AppVersion.isWellFormed("1.4.2")).isTrue();
        assertThat(AppVersion.isWellFormed("1.4.2.7")).isTrue();
        assertThat(AppVersion.isWellFormed("1.4.2.7.1")).isFalse();
        assertThat(AppVersion.isWellFormed("v1.4")).isFalse();
        assertThat(AppVersion.isWellFormed("1..4")).isFalse();
        assertThat(AppVersion.isWellFormed(null)).isFalse();
        assertThatThrownBy(() -> AppVersion.compare("latest", "1")).isInstanceOf(IllegalArgumentException.class);
    }
}
