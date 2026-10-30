package lk.coopfed.knoweb.m5inventory.internal.reprice;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.LossCategory;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.RepriceStockLot;
import lk.coopfed.knoweb.m5inventory.api.RequestWriteOff;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.StockRepriced;
import lk.coopfed.knoweb.m5inventory.api.StockRepricingResult;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LotBalance;
import lk.coopfed.knoweb.m5inventory.query.StockRepricingView;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for Stock Lot Repricing, Valuation Adjustments & Waste/Spoilage (PGL-M2-03).
 */
class RepriceStockPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e67d-0000-7000-8000-000000000002");
    private static final UUID OTHER_ENTITY = UUID.fromString("0190e67d-0000-7000-8000-000000000003");
    private static final LocalDate EXPIRY = LocalDate.of(2027, 12, 31);

    @Autowired
    Handles<RepriceStockLot, StockRepricingResult> repriceHandler;

    @Autowired
    Handles<RequestWriteOff, UUID> requestWriteOffHandler;

    @Autowired
    StockLedger ledger;

    @Autowired
    InventoryQueries queries;

    @Autowired
    OuterCommand outer;

    private InventoryFixture fixture;
    private UUID shop;
    private UUID otherShop;

    @BeforeEach
    void setUp() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        fixture.entity(MPCS, "M5RC", "MPCS");
        fixture.entity(OTHER_ENTITY, "M5OT", "MPCS");
        shop = fixture.location(MPCS, "SHOP");
        otherShop = fixture.location(OTHER_ENTITY, "SHOP");
        kernel.reset();
    }

    @AfterEach
    void tearDown() {
        fixture.clean();
    }

    @Test
    void fullLotRepriceAdjustsUnitCostAndEntityWeightedAverage() {
        UUID sku = fixture.sku(MPCS, "SKU_REPRICE_FULL");
        UUID batch = fixture.batch(sku, MPCS, "BAT_FULL", EXPIRY);
        ScopeContext scope = at(MPCS, shop);

        // Given 10 units at Rs 100
        postReceipt(shop, batch, new BigDecimal("10.000"), new BigDecimal("100.0000"), MPCS);
        kernel.reset();

        LotBalance lot = queries.balances(shop, sku, false, scope).get(0);
        assertThat(lot.unitCost()).isEqualByComparingTo("100.0000");

        // When repricing the full lot to Rs 70 as a top-level command
        StockRepricingResult result = repriceHandler.handle(
                new RepriceStockLot(
                        lot.stockLotId(),
                        new BigDecimal("10.000"),
                        new BigDecimal("70.0000"),
                        LotCondition.GOOD,
                        "Market price drop"),
                scope);

        // Then lot unit cost is updated to 70.0000
        LotBalance lotAfter = queries.balances(shop, sku, false, scope).get(0);
        assertThat(lotAfter.unitCost()).isEqualByComparingTo("70.0000");
        assertThat(lotAfter.qtyOnHand()).isEqualByComparingTo("10.000");

        // Entity weighted average cost is revalued to 70.0000
        BigDecimal avgCost = superuserJdbc()
                .queryForObject(
                        "select avg_cost from inventory.entity_sku_cost where owner_entity_id = ? and sku_id = ?",
                        BigDecimal.class,
                        MPCS,
                        sku);
        assertThat(avgCost).isEqualByComparingTo("70.0000");

        // Repricing ledger record exists
        List<StockRepricingView> repricings = queries.repricings(shop, sku, scope);
        assertThat(repricings).hasSize(1);
        StockRepricingView record = repricings.get(0);
        assertThat(record.repricingId()).isEqualTo(result.repricingId());
        assertThat(record.previousUnitCost()).isEqualByComparingTo("100.0000");
        assertThat(record.newUnitCost()).isEqualByComparingTo("70.0000");
        assertThat(record.valueAdjustment()).isEqualByComparingTo("-300.00");
        assertThat(record.reason()).isEqualTo("Market price drop");

        // Audit and domain event verified
        assertThat(audits(RepriceStockLotHandler.AUDIT_REPRICED)).hasSize(1);
        assertThat(events(StockRepriced.class)).hasSize(1);
    }

    @Test
    void partialRepriceInPlaceBlendsLotCostAndEntityWeightedAverage() {
        UUID sku = fixture.sku(MPCS, "SKU_REPRICE_PARTIAL");
        UUID batch = fixture.batch(sku, MPCS, "BAT_PART", EXPIRY);
        ScopeContext scope = at(MPCS, shop);

        // Given 10 units at Rs 100
        postReceipt(shop, batch, new BigDecimal("10.000"), new BigDecimal("100.0000"), MPCS);
        kernel.reset();
        LotBalance lot = queries.balances(shop, sku, false, scope).get(0);

        // When partially repricing 4 units in-place to Rs 70: (4*70 + 6*100) / 10 = 88.0000
        repriceHandler.handle(
                new RepriceStockLot(
                        lot.stockLotId(),
                        new BigDecimal("4.000"),
                        new BigDecimal("70.0000"),
                        LotCondition.GOOD,
                        "Partial stock discount"),
                scope);

        LotBalance lotAfter = queries.balances(shop, sku, false, scope).get(0);
        assertThat(lotAfter.unitCost()).isEqualByComparingTo("88.0000");
        assertThat(lotAfter.qtyOnHand()).isEqualByComparingTo("10.000");

        BigDecimal avgCost = superuserJdbc()
                .queryForObject(
                        "select avg_cost from inventory.entity_sku_cost where owner_entity_id = ? and sku_id = ?",
                        BigDecimal.class,
                        MPCS,
                        sku);
        assertThat(avgCost).isEqualByComparingTo("88.0000");
    }

    @Test
    void partialRepriceWithConditionDowngradeCreatesTargetLot() {
        UUID sku = fixture.sku(MPCS, "SKU_REPRICE_DOWNGRADE");
        UUID batch = fixture.batch(sku, MPCS, "BAT_DOWN", EXPIRY);
        ScopeContext scope = at(MPCS, shop);

        // Given 10 units at Rs 100 in GOOD condition
        postReceipt(shop, batch, new BigDecimal("10.000"), new BigDecimal("100.0000"), MPCS);
        kernel.reset();
        LotBalance lot = queries.balances(shop, sku, false, scope).get(0);

        // When repricing 3 units with condition change to DAMAGED at Rs 50
        StockRepricingResult result = repriceHandler.handle(
                new RepriceStockLot(
                        lot.stockLotId(),
                        new BigDecimal("3.000"),
                        new BigDecimal("50.0000"),
                        LotCondition.DAMAGED,
                        "Water damage markdown"),
                scope);

        // Source lot reduced to 7 units at Rs 100
        List<LotBalance> lotsAfter = queries.balances(shop, sku, false, scope);
        assertThat(lotsAfter).hasSize(2);

        LotBalance goodLot = lotsAfter.stream()
                .filter(l -> "GOOD".equals(l.condition()))
                .findFirst()
                .orElseThrow();
        assertThat(goodLot.qtyOnHand()).isEqualByComparingTo("7.000");
        assertThat(goodLot.unitCost()).isEqualByComparingTo("100.0000");

        LotBalance damagedLot = lotsAfter.stream()
                .filter(l -> "DAMAGED".equals(l.condition()))
                .findFirst()
                .orElseThrow();
        assertThat(damagedLot.qtyOnHand()).isEqualByComparingTo("3.000");
        assertThat(damagedLot.unitCost()).isEqualByComparingTo("50.0000");
        assertThat(damagedLot.stockLotId()).isEqualTo(result.targetLotId());

        // Entity weighted average cost is revalued: (7*100 + 3*50)/10 = 85.0000
        BigDecimal avgCost = superuserJdbc()
                .queryForObject(
                        "select avg_cost from inventory.entity_sku_cost where owner_entity_id = ? and sku_id = ?",
                        BigDecimal.class,
                        MPCS,
                        sku);
        assertThat(avgCost).isEqualByComparingTo("85.0000");
    }

    @Test
    void guardsPreventInvalidInputs() {
        UUID sku = fixture.sku(MPCS, "SKU_REPRICE_GUARDS");
        UUID batch = fixture.batch(sku, MPCS, "BAT_GUARDS", EXPIRY);
        ScopeContext scope = at(MPCS, shop);

        postReceipt(shop, batch, new BigDecimal("5.000"), new BigDecimal("50.0000"), MPCS);
        kernel.reset();
        LotBalance lot = queries.balances(shop, sku, false, scope).get(0);

        // 1. Negative cost
        assertThatThrownBy(() -> repriceHandler.handle(
                        new RepriceStockLot(
                                lot.stockLotId(),
                                new BigDecimal("2.000"),
                                new BigDecimal("-10.0000"),
                                LotCondition.GOOD,
                                "reason"),
                        scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m5.reprice.invalid_cost");

        // 2. Quantity exceeds available
        assertThatThrownBy(() -> repriceHandler.handle(
                        new RepriceStockLot(
                                lot.stockLotId(),
                                new BigDecimal("10.000"),
                                new BigDecimal("40.0000"),
                                LotCondition.GOOD,
                                "reason"),
                        scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m5.reprice.qty_exceeded");

        // 3. Blank reason
        assertThatThrownBy(() -> repriceHandler.handle(
                        new RepriceStockLot(
                                lot.stockLotId(),
                                new BigDecimal("2.000"),
                                new BigDecimal("40.0000"),
                                LotCondition.GOOD,
                                "   "),
                        scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m5.reprice.reason_required");

        // 4. Non-existent lot
        assertThatThrownBy(() -> repriceHandler.handle(
                        new RepriceStockLot(
                                UUID.randomUUID(),
                                new BigDecimal("2.000"),
                                new BigDecimal("40.0000"),
                                LotCondition.GOOD,
                                "reason"),
                        scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m5.reprice.not_found");
    }

    @Test
    void tenantIsolationUnderRls() {
        UUID sku = fixture.sku(MPCS, "SKU_REPRICE_TENANT");
        UUID batch = fixture.batch(sku, MPCS, "BAT_TENANT", EXPIRY);
        ScopeContext scope = at(MPCS, shop);

        postReceipt(shop, batch, new BigDecimal("5.000"), new BigDecimal("50.0000"), MPCS);
        kernel.reset();
        LotBalance lot = queries.balances(shop, sku, false, scope).get(0);

        ScopeContext otherScope = at(OTHER_ENTITY, otherShop);

        // Attempt to reprice by another entity is rejected (lot invisible under other entity's RLS scope)
        assertThatThrownBy(() -> repriceHandler.handle(
                        new RepriceStockLot(
                                lot.stockLotId(),
                                new BigDecimal("2.000"),
                                new BigDecimal("40.0000"),
                                LotCondition.GOOD,
                                "cross-tenant attempt"),
                        otherScope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m5.reprice.not_found");
    }

    @Test
    void wasteAndSpoilageWriteOffCategoriesSupported() {
        UUID sku = fixture.sku(MPCS, "SKU_WRITE_OFF_CATEGORIES");
        UUID batch = fixture.batch(sku, MPCS, "BAT_WOF", EXPIRY);
        ScopeContext scope = at(MPCS, shop);

        postReceipt(shop, batch, new BigDecimal("10.000"), new BigDecimal("100.0000"), MPCS);
        kernel.reset();

        // Request write-off with WASTE category as a top-level command
        UUID wasteWriteOffId = requestWriteOffHandler.handle(
                new RequestWriteOff(
                        shop,
                        LossCategory.WASTE,
                        "Damaged packaging waste",
                        List.of(new RequestWriteOff.Line(batch, LotCondition.GOOD, new BigDecimal("1.000")))),
                scope);

        assertThat(wasteWriteOffId).isNotNull();

        // Request write-off with SPOILAGE category as a top-level command
        UUID spoilageWriteOffId = requestWriteOffHandler.handle(
                new RequestWriteOff(
                        shop,
                        LossCategory.SPOILAGE,
                        "Perishable produce spoilage",
                        List.of(new RequestWriteOff.Line(batch, LotCondition.GOOD, new BigDecimal("1.000")))),
                scope);

        assertThat(spoilageWriteOffId).isNotNull();

        String wasteCategoryInDb = superuserJdbc()
                .queryForObject(
                        "select category from inventory.write_off where write_off_id = ?",
                        String.class,
                        wasteWriteOffId);
        assertThat(wasteCategoryInDb).isEqualTo("WASTE");

        String spoilageCategoryInDb = superuserJdbc()
                .queryForObject(
                        "select category from inventory.write_off where write_off_id = ?",
                        String.class,
                        spoilageWriteOffId);
        assertThat(spoilageCategoryInDb).isEqualTo("SPOILAGE");
    }

    private void postReceipt(UUID loc, UUID itemBatch, BigDecimal qty, BigDecimal unitCost, UUID entity) {
        ScopeContext scope = at(entity, loc);
        Movement m = new Movement(loc, itemBatch, LotCondition.GOOD, MovementType.RECEIPT, qty, unitCost, null);
        outer.run(scope, () -> ledger.post(new PostMovements(Ids.next(), null, null, List.of(m)), scope));
    }

    private List<KernelRecorder.AuditRecord> audits(String type) {
        return kernel.committedAudit().stream()
                .filter(a -> type.equals(a.eventType()))
                .toList();
    }

    private <T extends DomainEvent> List<T> events(Class<T> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }
}
