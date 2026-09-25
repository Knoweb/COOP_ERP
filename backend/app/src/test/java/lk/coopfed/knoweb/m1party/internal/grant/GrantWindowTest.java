package lk.coopfed.knoweb.m1party.internal.grant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.BiFunction;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.junit.jupiter.api.Test;

/** The window guard of GrantExternalView, case by case (doc 21 section 4.6; DR-4, doc 10 L-07). */
class GrantWindowTest {

    private static final Instant NOW = Instant.parse("2026-09-25T04:00:00Z");

    private static final BiFunction<Instant, Integer, Instant> UTC_MONTHS =
            (from, months) -> from.atOffset(ZoneOffset.UTC).plusMonths(months).toInstant();

    @Test
    void aMissingStartIsNow() {
        GrantWindow.Window window = GrantWindow.of(null, NOW.plusSeconds(3600), NOW, 12, UTC_MONTHS);

        assertThat(window.validFrom()).isEqualTo(NOW);
        assertThat(window.validUntil()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void aStartInThePastIsNow() {
        GrantWindow.Window window =
                GrantWindow.of(NOW.minusSeconds(86_400), NOW.plusSeconds(3600), NOW, 12, UTC_MONTHS);

        assertThat(window.validFrom()).isEqualTo(NOW);
    }

    @Test
    void aStartInTheFutureIsKept() {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");

        assertThat(GrantWindow.of(from, from.plusSeconds(60), NOW, 12, UTC_MONTHS)
                        .validFrom())
                .isEqualTo(from);
    }

    @Test
    void exactlyTwelveMonthsIsAllowed() {
        Instant until = Instant.parse("2027-09-25T04:00:00Z");

        assertThat(GrantWindow.of(null, until, NOW, 12, UTC_MONTHS).validUntil())
                .isEqualTo(until);
    }

    @Test
    void oneMicrosecondMoreThanTwelveMonthsIsRefused() {
        Instant until = Instant.parse("2027-09-25T04:00:00.000001Z");

        assertRefused(() -> GrantWindow.of(null, until, NOW, 12, UTC_MONTHS), "m1.grant.window_too_long");
    }

    @Test
    void theConfiguredMaximumShortensTheWindow() {
        Instant until = Instant.parse("2026-12-26T04:00:00Z");

        assertRefused(() -> GrantWindow.of(null, until, NOW, 3, UTC_MONTHS), "m1.grant.window_too_long");
    }

    @Test
    void theConfiguredMaximumNeverLengthensItBeyondTwelveMonths() {
        Instant until = Instant.parse("2028-01-01T00:00:00Z");

        assertRefused(() -> GrantWindow.of(null, until, NOW, 36, UTC_MONTHS), "m1.grant.window_too_long");
    }

    @Test
    void anEndBeforeTheStartIsRefused() {
        Instant from = Instant.parse("2026-10-10T00:00:00Z");

        assertRefused(() -> GrantWindow.of(from, from.minusSeconds(1), NOW, 12, UTC_MONTHS), "m1.grant.window_invalid");
    }

    @Test
    void anEndEqualToTheStartIsRefused() {
        assertRefused(() -> GrantWindow.of(null, NOW, NOW, 12, UTC_MONTHS), "m1.grant.window_invalid");
    }

    @Test
    void anEndAlreadyPassedIsRefused() {
        assertRefused(() -> GrantWindow.of(null, NOW.minusSeconds(1), NOW, 12, UTC_MONTHS), "m1.grant.window_invalid");
    }

    @Test
    void aMissingEndIsRefused() {
        assertRefused(() -> GrantWindow.of(null, null, NOW, 12, UTC_MONTHS), "m1.grant.window_invalid");
    }

    @Test
    void theWindowIsHeldToMicroseconds() {
        Instant from = Instant.parse("2026-10-01T00:00:00.123456789Z");

        GrantWindow.Window window = GrantWindow.of(from, from.plusSeconds(60), NOW, 12, UTC_MONTHS);

        assertThat(window.validFrom()).isEqualTo(Instant.parse("2026-10-01T00:00:00.123456Z"));
        assertThat(window.validUntil()).isEqualTo(Instant.parse("2026-10-01T00:01:00.123456Z"));
    }

    private static void assertRefused(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                .isEqualTo(code));
    }
}
