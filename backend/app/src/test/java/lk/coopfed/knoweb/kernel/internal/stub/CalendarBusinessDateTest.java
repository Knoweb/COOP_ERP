package lk.coopfed.knoweb.kernel.internal.stub;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarBusinessDateTest {

    private static final UUID SHOP = UUID.randomUUID();

    @Test
    void theDateIsTheOneInTheBusinessZoneNotInUtc() {
        // 20:00 UTC on the 19th is 01:30 on the 20th in Colombo.
        Clock evening = Clock.fixed(Instant.parse("2026-09-19T20:00:00Z"), ZoneOffset.UTC);

        assertThat(new CalendarBusinessDate(evening, "Asia/Colombo").current(SHOP))
                .isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(new CalendarBusinessDate(evening, "UTC").current(SHOP))
                .isEqualTo(LocalDate.of(2026, 9, 19));
    }

    @Test
    void aBusinessDateBelongsToALocation() {
        CalendarBusinessDate businessDate = new CalendarBusinessDate(Clock.systemUTC(), "Asia/Colombo");

        assertThatThrownBy(() -> businessDate.current(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("belongs to a location");
    }
}
