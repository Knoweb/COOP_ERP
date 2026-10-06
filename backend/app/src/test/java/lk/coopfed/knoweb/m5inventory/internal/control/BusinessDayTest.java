package lk.coopfed.knoweb.m5inventory.internal.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The business date stock is judged by (wave 2, M5-01): Colombo's date from the clock, never the
 * database's UTC {@code current_date}; a lot is expired only after its printed date.
 */
class BusinessDayTest {

    private static final String COLOMBO = "Asia/Colombo";

    @Test
    void justAfterMidnightInColomboItIsAlreadyTheNextDayThoughUtcIsStillOnTheOne() {
        // 18:31 UTC on 6 October is 00:01 on 7 October in Colombo (UTC+05:30).
        BusinessDay day = new BusinessDay(Clock.fixed(Instant.parse("2026-10-06T18:31:00Z"), ZoneOffset.UTC), COLOMBO);

        assertThat(day.today()).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(day.dateOf(Instant.parse("2026-10-06T18:29:00Z"))).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(day.dateOf(null)).isEqualTo(LocalDate.of(2026, 10, 7));
    }

    @Test
    void aLotExpiringTodaySellsTodayAndIsExpiredTomorrow() {
        LocalDate printed = LocalDate.of(2026, 10, 7);

        assertThat(BusinessDay.expired(printed, LocalDate.of(2026, 10, 6))).isFalse();
        assertThat(BusinessDay.expired(printed, LocalDate.of(2026, 10, 7))).isFalse();
        assertThat(BusinessDay.expired(printed, LocalDate.of(2026, 10, 8))).isTrue();
        assertThat(BusinessDay.expired(null, LocalDate.of(2026, 10, 8)))
                .as("an item without an expiry never expires")
                .isFalse();
    }
}
