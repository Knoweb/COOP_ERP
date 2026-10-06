package lk.coopfed.knoweb.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static Map<Item, BigDecimal> held(int count, int qty) {
        Map<Item, BigDecimal> held = new LinkedHashMap<>();
        DemoCatalogue.load().stream()
                .filter(item -> item.barcode() != null)
                .limit(count)
                .forEach(item -> held.put(item, BigDecimal.valueOf(qty)));
        return held;
    }

    @Test
    void each_day_is_one_or_two_varied_baskets_of_one_to_five_items_the_shop_holds() {
        Map<Item, BigDecimal> held = held(12, 1000);
        Set<String> barcodes = new HashSet<>();
        held.keySet().forEach(item -> barcodes.add(item.barcode()));
        List<LocalDate> plan = DemoTillHistory.saleDays(TODAY, DemoCalendar.HISTORY_DAYS);

        Map<LocalDate, List<List<Sale>>> sales = DemoTillHistory.salesOf(DemoCast.M101_TOWN_SHOP, plan, plan, held);

        assertThat(sales.keySet()).containsExactlyElementsOf(plan);
        assertThat(sales.get(plan.get(0))).hasSize(2);
        assertThat(sales.get(plan.get(1))).hasSize(1);
        Set<List<Sale>> baskets = new HashSet<>();
        Set<Integer> sizes = new HashSet<>();
        Set<Integer> qtys = new HashSet<>();
        for (List<List<Sale>> day : sales.values()) {
            for (List<Sale> sale : day) {
                baskets.add(sale);
                sizes.add(sale.size());
                assertThat(sale).hasSizeBetween(1, 5);
                assertThat(sale.stream().map(Sale::barcode)).doesNotHaveDuplicates();
                for (Sale line : sale) {
                    assertThat(barcodes).contains(line.barcode());
                    assertThat(line.qty().intValue()).isBetween(1, 3);
                    assertThat(line.unitPrice()).isPositive();
                    qtys.add(line.qty().intValue());
                }
            }
        }
        // Not the same basket every time (before: 2 bath soap and 1 laundry soap on every receipt).
        assertThat(baskets).hasSizeGreaterThan(20);
        assertThat(sizes).hasSizeGreaterThan(3);
        assertThat(qtys).containsExactlyInAnyOrder(1, 2, 3);
        // The same plan on every run, and another at another shop.
        assertThat(DemoTillHistory.salesOf(DemoCast.M101_TOWN_SHOP, plan, plan, held))
                .isEqualTo(sales);
        assertThat(DemoTillHistory.salesOf(DemoCast.M102_SHOP, plan, plan, held))
                .isNotEqualTo(sales);
    }

    @Test
    void the_history_never_sells_more_than_the_shop_holds_and_spreads_it_over_the_days() {
        Map<Item, BigDecimal> held = held(2, 12);
        List<LocalDate> plan = DemoTillHistory.saleDays(TODAY, DemoCalendar.HISTORY_DAYS);

        Map<LocalDate, List<List<Sale>>> sales = DemoTillHistory.salesOf(DemoCast.M101_TOWN_SHOP, plan, plan, held);

        Map<String, Integer> sold = new HashMap<>();
        sales.values()
                .forEach(day -> day.forEach(sale -> sale.forEach(
                        line -> sold.merge(line.barcode(), line.qty().intValue(), Integer::sum))));
        for (Item item : held.keySet()) {
            assertThat(sold.getOrDefault(item.barcode(), 0))
                    .isPositive()
                    .isLessThanOrEqualTo(12 - DemoTillHistory.RESERVE);
        }
        // Not all sold in the first days: the last week still sells.
        assertThat(sales.get(plan.get(plan.size() - 1))).isNotEmpty();
    }

    /**
     * Every demo shop's first stock (DemoShopStock) sells on every sale day of its plan, one receipt
     * per planned sale, in varied baskets: the four shops fill their history (before: the town shop
     * held ten or more of two items only, and the other three shops nothing to sell).
     */
    @Test
    void every_demo_shop_has_stock_to_sell_on_every_sale_day_in_varied_baskets() {
        Map<String, Item> catalogue = new HashMap<>();
        DemoCatalogue.load().forEach(item -> catalogue.put(item.nameEn(), item));
        // Three sale days a week, one or two sales a day (36 planned over the eight weeks, 18 over
        // Hettipola's four); the eight-week shops make one fewer, a second basket of a day that found
        // its items already sold up to that day's share. The plan depends on the shop and the day's
        // place in it, never on the date, so these are the receipts of a fresh demo.
        Map<java.util.UUID, Integer> expectedReceipts = Map.of(
                DemoCast.M101_TOWN_SHOP, 35,
                DemoCast.M101_HETTIPOLA_SHOP, 18,
                DemoCast.M102_SHOP, 35,
                DemoCast.M103_SHOP, 35);
        assertThat(DemoShopStock.BY_SHOP).containsOnlyKeys(expectedReceipts.keySet());
        Map<java.util.UUID, Integer> actualReceipts = new HashMap<>();
        for (DemoCast.Shop shop : DemoCast.SHOPS) {
            List<DemoShopStock.Shelf> shelves = DemoShopStock.BY_SHOP.get(shop.locationId());
            Map<Item, BigDecimal> held = new LinkedHashMap<>();
            for (DemoShopStock.Shelf shelf : shelves) {
                Item item = catalogue.get(shelf.nameEn());
                assertThat(item).as(shelf.nameEn()).isNotNull();
                assertThat(item.barcode())
                        .as(shelf.nameEn() + " is sold by barcode")
                        .isNotNull();
                assertThat(shelf.qty()).as(shelf.nameEn()).isGreaterThanOrEqualTo(10);
                held.put(item, BigDecimal.valueOf(shelf.qty()));
            }
            assertThat(held)
                    .as("an everyday range, each item once")
                    .hasSizeBetween(15, 25)
                    .hasSize(shelves.size());
            List<LocalDate> plan =
                    DemoTillHistory.saleDays(TODAY, DemoTillHistory.FIRST_DAY_AGO.get(shop.locationId()));

            Map<LocalDate, List<List<Sale>>> sales = DemoTillHistory.salesOf(shop.locationId(), plan, plan, held);

            assertThat(sales.values()).as("a sale on every day").allMatch(day -> !day.isEmpty());
            List<List<Sale>> receipts =
                    sales.values().stream().flatMap(List::stream).toList();
            actualReceipts.put(shop.locationId(), receipts.size());
            Set<BigDecimal> totals = new HashSet<>();
            Set<Integer> sizes = new HashSet<>();
            for (List<Sale> receipt : receipts) {
                totals.add(receipt.stream()
                        .map(line -> line.qty().multiply(line.unitPrice()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
                sizes.add(receipt.size());
            }
            assertThat(totals).as("distinct totals").hasSizeGreaterThan(receipts.size() * 3 / 4);
            assertThat(sizes).containsAnyOf(1, 2).containsAnyOf(4, 5);
        }
        assertThat(actualReceipts).isEqualTo(expectedReceipts);
    }

    /**
     * Kuliyapitiya's stores (half a distributor's quantity of each item, DemoDataLoader) hold both
     * shops' first transfers from the opening stock alone, and keep what the stock operations need:
     * three of the counted dhal and sugar, two of the flour written off.
     */
    @Test
    void the_society_stores_hold_both_kuliyapitiya_shops_first_transfers() {
        Map<String, Integer> sent = new HashMap<>();
        DemoShopStock.TOWN_SHOP.forEach(shelf -> sent.merge(shelf.nameEn(), shelf.qty(), Integer::sum));
        DemoShopStock.HETTIPOLA_SHOP.forEach(shelf -> sent.merge(shelf.nameEn(), shelf.qty(), Integer::sum));
        for (Item item : DemoCatalogue.load()) {
            int stores = item.distributorQty().intValue() / 2;
            int keep =
                    switch (item.nameEn()) {
                        case DemoStockOperations.COUNTED_ITEM_SHORT_ONE, DemoStockOperations.COUNTED_ITEM_SHORT_THREE ->
                            3;
                        case DemoStockOperations.DAMAGED_ITEM -> 2;
                        default -> 0;
                    };
            assertThat(stores - sent.getOrDefault(item.nameEn(), 0))
                    .as(item.nameEn() + " left at the stores")
                    .isGreaterThanOrEqualTo(keep);
        }
    }

    @Test
    void a_run_cut_short_plans_only_the_days_left() {
        Map<Item, BigDecimal> held = held(8, 500);
        List<LocalDate> plan = DemoTillHistory.saleDays(TODAY, DemoCalendar.HISTORY_DAYS);
        List<LocalDate> todo = plan.subList(10, plan.size());

        assertThat(DemoTillHistory.salesOf(DemoCast.M103_SHOP, plan, todo, held))
                .containsOnlyKeys(todo);
    }
}
