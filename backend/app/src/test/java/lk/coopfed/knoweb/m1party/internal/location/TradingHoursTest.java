package lk.coopfed.knoweb.m1party.internal.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m1party.api.TradingDay;
import org.junit.jupiter.api.Test;

class TradingHoursTest {

    @Test
    void daysGoToJsonAndComeBackTheSame() {
        List<TradingDay> week =
                List.of(new TradingDay("MON", "08:00", "20:00"), new TradingDay("SAT", "08:00", "13:30"));

        assertThat(TradingHours.fromJson(TradingHours.toJson(week))).containsExactlyElementsOf(week);
    }

    @Test
    void noHoursAreNoHours() {
        assertThat(TradingHours.toJson(null)).isNull();
        assertThat(TradingHours.fromJson(null)).isEmpty();
    }

    @Test
    void aDayClosesAfterItOpensAndAppearsOnce() {
        assertInvalid(List.of(new TradingDay("MON", "20:00", "08:00")));
        assertInvalid(List.of(new TradingDay("MON", "08:00", "08:00")));
        assertInvalid(List.of(new TradingDay("MON", "08:00", "12:00"), new TradingDay("MON", "13:00", "17:00")));
        assertInvalid(List.of(new TradingDay("MON", "8 am", "17:00")));
        assertInvalid(List.of(new TradingDay("MON", null, "17:00")));
    }

    private static void assertInvalid(List<TradingDay> days) {
        assertThatThrownBy(() -> TradingHours.toJson(days))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m1.location.trading_hours_invalid");
    }
}
