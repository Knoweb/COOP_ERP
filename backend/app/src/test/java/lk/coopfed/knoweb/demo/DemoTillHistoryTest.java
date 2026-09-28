package lk.coopfed.knoweb.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lk.coopfed.knoweb.demo.DemoCatalogue.Item;
import lk.coopfed.knoweb.testsupport.TillSimulator.Sale;
import org.junit.jupiter.api.Test;

/** The plan of the demo's till history (DEMO-02b): which days each shop sells, and what. */
class DemoTillHistoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

    @Test
    void a_shop_sells_three_days_a_week_over_the_eight_weeks_oldest_first_and_never_today() {
        List<LocalDate> days = DemoTillHistory.saleDays(TODAY, DemoCalendar.HISTORY_DAYS);

        assertThat(days).first().isEqualTo(TODAY.minusDays(DemoCalendar.HISTORY_DAYS));
        assertThat(days).isSorted().doesNotHaveDuplicates().allMatch(day -> day.isBefore(TODAY));
        assertThat(days).hasSize(24);
        for (int week = 0; week < 8; week++) {
            LocalDate start = TODAY.minusDays(DemoCalendar.HISTORY_DAYS - 7L * week);
            assertThat(days)
                    .filteredOn(d -> !d.isBefore(start) && d.isBefore(start.plusDays(7)))
                    .hasSize(3);
        }
    }

    @Test
    void a_shop_sells_nothing_before_it_had_stock() {
        int hettipola = DemoTillHistory.FIRST_DAY_AGO.get(DemoCast.M101_HETTIPOLA_SHOP);

        List<LocalDate> days = DemoTillHistory.saleDays(TODAY, hettipola);

        assertThat(days).isNotEmpty().allMatch(day -> !day.isBefore(TODAY.minusDays(hettipola)));
        assertThat(DemoTillHistory.FIRST_DAY_AGO)
                .containsOnlyKeys(
                        DemoCast.SHOPS.stream().map(DemoCast.Shop::locationId).toList());
    }

    @Test
    void a_second_run_finds_every_day_sold_and_sells_nothing() {
        List<LocalDate> plan = DemoTillHistory.saleDays(TODAY, DemoCalendar.HISTORY_DAYS);

        assertThat(DemoTillHistory.stillToSell(plan, Set.of())).isEqualTo(plan);
        assertThat(DemoTillHistory.stillToSell(plan, new HashSet<>(plan))).isEmpty();
        // A run cut short goes on from the first day without a receipt.
        Set<LocalDate> half = new HashSet<>(plan.subList(0, 10));
        assertThat(DemoTillHistory.stillToSell(plan, half)).isEqualTo(plan.subList(10, plan.size()));
    }

    @Test
    void each_day_is_one_or_two_sales_of_two_barcoded_items_one_or_two_of_each() {
        List<Item> candidates = DemoCatalogue.load().stream()
                .filter(item -> item.barcode() != null)
                .limit(6)
                .toList();

        assertThat(DemoTillHistory.salesOf(0, candidates)).hasSize(2);
        assertThat(DemoTillHistory.salesOf(1, candidates)).hasSize(1);
        for (int day = 0; day < 24; day++) {
            for (List<Sale> sale : DemoTillHistory.salesOf(day, candidates)) {
                assertThat(sale).hasSize(2).allSatisfy(line -> {
                    assertThat(line.barcode()).isNotBlank();
                    assertThat(line.qty().intValue()).isBetween(1, 2);
                    assertThat(line.unitPrice()).isPositive();
                });
            }
        }
        // The same plan on every run: the history is the same demo each time it is loaded.
        assertThat(DemoTillHistory.salesOf(5, candidates)).isEqualTo(DemoTillHistory.salesOf(5, candidates));
    }
}
