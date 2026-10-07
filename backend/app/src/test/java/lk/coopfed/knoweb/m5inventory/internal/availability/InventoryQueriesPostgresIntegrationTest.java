package lk.coopfed.knoweb.m5inventory.internal.availability;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.at;
import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.CorrectBatch;
import lk.coopfed.knoweb.m2catalogue.api.InventoryLotQuery;
import lk.coopfed.knoweb.m2catalogue.internal.batch.CorrectBatchHandler;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.internal.control.BusinessDay;
import lk.coopfed.knoweb.m5inventory.query.Availability;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LotBalance;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The queries of 25A section 7 against real lots (M5-04 "done when": dependants' tests pass
 * against real queries): balances and the FEFO rank, availability without negative or damaged
 * lots, pick order, in-stock batches, the SKUs with lots, the entity average, LotsConsumed; the
 * row-level security of the reads; and M2's questions answered by M5 (InventoryLotQuery), with
 * M2's CorrectBatch now asking the real lots.
 */
class InventoryQueriesPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e676-0000-7000-8000-000000000002");
    private static final UUID OTHER = UUID.fromString("0190e676-0000-7000-8000-000000000003");

    @Autowired
    InventoryQueries queries;

    @Autowired
    InventoryLotQuery lotQuestions;

    @Autowired
    StockLedger ledger;

    @Autowired
    OuterCommand outer;

    @Autowired
    CorrectBatchHandler correct;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    BusinessDay businessDay;

    private InventoryFixture fixture;
    private UUID warehouse;
    private UUID shop;
    private UUID sku;
    private UUID otherSku;
    private UUID early;
    private UUID late;
    private UUID undated;

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        warehouse = fixture.location(MPCS, "WAREHOUSE");
        shop = fixture.location(MPCS, "SHOP");
        sku = fixture.sku(MPCS, "RICE5");
        otherSku = fixture.sku(MPCS, "DHAL1");
        late = fixture.batch(sku, MPCS, "LATE", LocalDate.of(2027, 6, 30));
        undated = fixture.batch(sku, MPCS, "UNDATED", null);
        early = fixture.batch(sku, MPCS, "EARLY", LocalDate.of(2027, 3, 31));
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void balancesRankTheGoodLotsWithStockByExpiryThenReceiptAndLeaveOutEmptyLots() {
        UUID other = fixture.batch(otherSku, MPCS, "D1", LocalDate.of(2027, 1, 1));
        post(
                own(MPCS),
                receipt(warehouse, undated, LotCondition.GOOD, "5"),
                receipt(warehouse, late, LotCondition.GOOD, "7"),
                receipt(warehouse, early, LotCondition.GOOD, "3"),
                receipt(warehouse, early, LotCondition.DAMAGED, "1"),
                receipt(warehouse, other, LotCondition.GOOD, "2"));
        post(own(MPCS), issue(warehouse, other, "-2"));

        List<LotBalance> lots = queries.balances(warehouse, sku, false, own(MPCS));
        assertThat(lots)
                .extracting(LotBalance::batchId, LotBalance::condition, LotBalance::fefoRank)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(early, "GOOD", 1),
                        org.assertj.core.groups.Tuple.tuple(late, "GOOD", 2),
                        org.assertj.core.groups.Tuple.tuple(undated, "GOOD", 3),
                        org.assertj.core.groups.Tuple.tuple(early, "DAMAGED", null));
        assertThat(lots.get(0).unitCost()).isEqualByComparingTo("100");

        assertThat(queries.balances(warehouse, null, false, own(MPCS))).hasSize(4);
        assertThat(queries.balances(warehouse, null, true, own(MPCS))).hasSize(5);
        assertThat(queries.pickBatches(warehouse, sku, own(MPCS)))
                .extracting(LotBalance::batchId)
                .containsExactly(early, late, undated);
        assertThat(queries.inStockBatches(List.of(warehouse, shop), sku, own(MPCS)))
                .extracting(LotBalance::batchId)
                .containsExactly(early, late, undated);
        assertThat(queries.skusWithLots(warehouse, own(MPCS))).containsExactly(sku);
    }

    @Test
    void anExpiredLotIsShownFlaggedButNeverRankedAvailablePickedOrPricedWhileALotExpiringTodayStillIs() {
        LocalDate today = businessDay.today();
        UUID gone = fixture.batch(sku, MPCS, "GONE", today.minusDays(1));
        UUID lastDay = fixture.batch(sku, MPCS, "LASTDAY", today);
        post(
                own(MPCS),
                receipt(warehouse, gone, LotCondition.GOOD, "6"),
                receipt(warehouse, lastDay, LotCondition.GOOD, "4"),
                receipt(warehouse, late, LotCondition.GOOD, "5"));

        List<LotBalance> lots = queries.balances(warehouse, sku, false, own(MPCS));
        BigDecimal available =
                available(queries.availability(List.of(warehouse), List.of(sku), own(MPCS)), warehouse, sku);
        List<LotBalance> picks = queries.pickBatches(warehouse, sku, own(MPCS));
        List<LotBalance> priced = queries.inStockBatches(List.of(warehouse), sku, own(MPCS));
        // The business date is read again: a run across midnight in Colombo proves nothing, so it is skipped.
        assumeTrue(today.equals(businessDay.today()), "the run crossed midnight in Colombo");

        assertThat(lots)
                .extracting(LotBalance::batchId, LotBalance::fefoRank, LotBalance::expired)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(lastDay, 1, false),
                        org.assertj.core.groups.Tuple.tuple(late, 2, false),
                        org.assertj.core.groups.Tuple.tuple(gone, null, true));
        assertThat(available).isEqualByComparingTo("9");
        assertThat(picks).extracting(LotBalance::batchId).containsExactly(lastDay, late);
        assertThat(priced).extracting(LotBalance::batchId).containsExactly(lastDay, late);
    }

    @Test
    void pickBatchesLeaveOutWhatAnOpenPickListHolds() {
        post(
                own(MPCS),
                receipt(warehouse, early, LotCondition.GOOD, "10"),
                receipt(warehouse, late, LotCondition.GOOD, "5"));
        UUID earlyLot =
                queries.balances(warehouse, sku, false, own(MPCS)).get(0).stockLotId();
        UUID pickList = Ids.next();
        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "insert into inventory.pick_list (pick_list_id, owner_entity_id, delivery_document_id) values (?, ?, ?)",
                pickList,
                MPCS,
                Ids.next());
        admin.update(
                """
                insert into inventory.pick_list_line
                    (pick_line_id, pick_list_id, owner_entity_id, delivery_line_id, sku_id, location_id, stock_lot_id,
                     batch_id, qty)
                values (?, ?, ?, ?, ?, ?, ?, ?, 7)
                """,
                Ids.next(),
                pickList,
                MPCS,
                Ids.next(),
                sku,
                warehouse,
                earlyLot,
                early);

        assertThat(queries.pickBatches(warehouse, sku, own(MPCS)))
                .extracting(LotBalance::batchId, l -> l.qtyOnHand().intValue())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(early, 3), org.assertj.core.groups.Tuple.tuple(late, 5));
    }

    @Test
    void availabilityCountsGoodLotsWithStockOnlyAndAnswersEveryPair() {
        post(
                own(MPCS),
                receipt(warehouse, early, LotCondition.GOOD, "10"),
                receipt(warehouse, late, LotCondition.GOOD, "4"),
                receipt(warehouse, late, LotCondition.DAMAGED, "3"),
                receipt(shop, early, LotCondition.GOOD, "1"));
        // Two tills oversold the shop's last unit: the lot is at -2 and counts as nothing.
        post(at(MPCS, shop), issue(shop, early, "-3"));

        List<Availability> answer = queries.availability(List.of(warehouse, shop), List.of(sku, otherSku), own(MPCS));

        assertThat(answer).hasSize(4);
        assertThat(available(answer, warehouse, sku)).isEqualByComparingTo("14");
        assertThat(available(answer, shop, sku)).isEqualByComparingTo("0");
        assertThat(available(answer, warehouse, otherSku)).isEqualByComparingTo("0");
    }

    @Test
    void theEntityAverageAndLotsConsumed() {
        UUID grn = Ids.next();
        post(own(MPCS), grn, receipt(warehouse, early, LotCondition.GOOD, "10"));
        assertThat(queries.entityAverageCost(sku, own(MPCS))).hasValueSatisfying(c -> {
            assertThat(c.avgCost()).isEqualByComparingTo("100");
            assertThat(c.qtyOnHand()).isEqualByComparingTo("10");
        });
        assertThat(queries.entityAverageCost(sku, own(OTHER))).isEmpty();
        assertThat(queries.lotsConsumed(grn, own(MPCS))).isFalse();

        post(own(MPCS), issue(warehouse, early, "-1"));

        assertThat(queries.lotsConsumed(grn, own(MPCS))).isTrue();
    }

    @Test
    void theReadsShowTheCallersOwnLotsTheFederationViewAllAShopItsShop() {
        post(
                own(MPCS),
                receipt(warehouse, early, LotCondition.GOOD, "10"),
                receipt(shop, late, LotCondition.GOOD, "2"));

        assertThat(queries.balances(warehouse, null, false, own(OTHER))).isEmpty();
        assertThat(queries.balances(warehouse, null, false, at(MPCS, shop))).isEmpty();
        assertThat(queries.balances(shop, null, false, at(MPCS, shop))).hasSize(1);
        assertThat(queries.balances(warehouse, null, false, federationView())).hasSize(1);
    }

    // ---- M2's questions, answered by M5 -----------------------------------------------------

    @Test
    void catalogueQuestionsAreAnsweredFromTheLotsToAnOwnCallerAboutItself() {
        assertThat(outer.run(own(MPCS), () -> lotQuestions.hasAnyLot(sku))).isFalse();
        assertThat(outer.run(own(MPCS), () -> lotQuestions.holdsLotOf(early, own(MPCS))))
                .isFalse();

        post(own(MPCS), receipt(shop, early, LotCondition.GOOD, "1"));

        // Asked in another entity's scope (the Federation editing a shared item): the answer is
        // yes or no, without showing that entity the lot.
        assertThat(outer.run(own(OTHER), () -> lotQuestions.hasAnyLot(sku))).isTrue();
        // The holder question is about the caller (m5inventory V0007; wave 2, RLS-12): MPCS holds
        // a lot of the early batch, OTHER does not, and nobody holds one of the late batch.
        assertThat(outer.run(own(MPCS), () -> lotQuestions.holdsLotOf(early, own(MPCS))))
                .isTrue();
        assertThat(outer.run(own(OTHER), () -> lotQuestions.holdsLotOf(early, own(OTHER))))
                .isFalse();
        assertThat(outer.run(own(MPCS), () -> lotQuestions.holdsLotOf(late, own(MPCS))))
                .isFalse();

        // A class that is not OWN is answered nothing, in Java and in the database alike.
        assertThat(outer.run(federationView(), () -> lotQuestions.hasAnyLot(sku)))
                .isFalse();
        assertThat(outer.run(federationView(), () -> lotQuestions.holdsLotOf(early, federationView())))
                .isFalse();
        assertThat(outer.run(
                        federationView(),
                        () -> jdbc.queryForObject("select inventory.caller_holds_lot_of(?)", Boolean.class, early)))
                .isFalse();
        // The two-argument form of V0002 is the migrator's alone now (M2's correction trigger).
        assertThatThrownBy(() -> outer.run(
                        own(MPCS),
                        () -> jdbc.queryForObject(
                                "select inventory.entity_holds_lot_of(?, ?)", Boolean.class, early, MPCS)))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void aBatchIsCorrectedByAnEntityHoldingALotOfItAndRefusedToOneThatDoesNot() {
        // The batch is registered by OTHER (the supplier side); MPCS holds a lot of it.
        UUID otherItem = fixture.sku(OTHER, "SUGAR1");
        UUID theirs = fixture.batch(otherItem, OTHER, "S1", LocalDate.of(2027, 1, 31));
        post(own(MPCS), receipt(shop, theirs, LotCondition.GOOD, "4"));

        assertThatThrownBy(() -> correct.handle(
                        new CorrectBatch(theirs, null, LocalDate.of(2027, 2, 28), "EXPIRY_MISKEYED", null),
                        withMfa(OTHER)))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m2.batch.not_holder"));

        assertThat(correct.handle(
                        new CorrectBatch(theirs, null, LocalDate.of(2027, 2, 28), "EXPIRY_MISKEYED", null),
                        withMfa(MPCS)))
                .isNotNull();
    }

    // ---- helpers --------------------------------------------------------------------------

    private void post(ScopeContext scope, Movement... movements) {
        post(scope, Ids.next(), movements);
    }

    private void post(ScopeContext scope, UUID document, Movement... movements) {
        outer.run(scope, () -> ledger.post(new PostMovements(document, null, null, List.of(movements)), scope));
    }

    private static Movement receipt(UUID location, UUID batch, LotCondition condition, String qty) {
        return new Movement(
                location, batch, condition, MovementType.RECEIPT, new BigDecimal(qty), new BigDecimal("100"), null);
    }

    private static Movement issue(UUID location, UUID batch, String qty) {
        return new Movement(location, batch, LotCondition.GOOD, MovementType.SALE, new BigDecimal(qty), null, null);
    }

    private static BigDecimal available(List<Availability> answer, UUID location, UUID sku) {
        return answer.stream()
                .filter(a -> a.locationId().equals(location) && a.skuId().equals(sku))
                .findFirst()
                .orElseThrow()
                .available();
    }

    private static ScopeContext federationView() {
        Scope scope = new Scope(TEST_FEDERATION, null);
        return new ScopeContext(
                InventoryFixture.USER,
                null,
                TEST_FEDERATION,
                List.of(scope),
                scope,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);
    }

    private static ScopeContext withMfa(UUID entity) {
        Scope scope = new Scope(entity, null);
        return new ScopeContext(
                InventoryFixture.USER,
                null,
                entity,
                List.of(scope),
                scope,
                PolicyClass.OWN,
                Set.of(),
                java.time.Instant.now(),
                Locale.ENGLISH,
                null);
    }
}
