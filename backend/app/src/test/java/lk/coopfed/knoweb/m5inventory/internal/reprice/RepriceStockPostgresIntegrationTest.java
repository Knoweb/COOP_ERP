package lk.coopfed.knoweb.m5inventory.internal.reprice;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
import lk.coopfed.knoweb.m5inventory.api.StockRepricingResult;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LotBalance;
import lk.coopfed.knoweb.m5inventory.query.StockRepricingView;
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
    void blockedFullRepriceCreatesNoMovementCostChangeRepricingAuditOrOutbox() {
        UUID sku = fixture.sku(MPCS, "SKU_REPRICE_FULL");
        UUID batch = fixture.batch(sku, MPCS, "BAT_FULL", EXPIRY);
        ScopeContext scope = at(MPCS, shop);

        // Given 10 units at Rs 100
        postReceipt(shop, batch, new BigDecimal("10.000"), new BigDecimal("100.0000"), MPCS);
        kernel.reset();

        LotBalance lotBefore = queries.balances(shop, sku, false, scope).get(0);
        int movementsBefore = count("select count(*) from inventory.stock_movement where location_id = ?", shop);
        int lotsBefore = count("select count(*) from inventory.stock_lot where location_id = ?", shop);
        int repricingsBefore = count("select count(*) from inventory.stock_repricing where location_id = ?", shop);
        int outboxBefore = count("select count(*) from kernel.event_outbox");

        // When attempting full in-place repricing
        assertThatThrownBy(() -> repriceHandler.handle(
                        new RepriceStockLot(
                                lotBefore.stockLotId(),
                                new BigDecimal("10.000"),
                                new BigDecimal("70.0000"),
                                LotCondition.GOOD,
                                "Market price drop"),
                        scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage(RepriceStockLotHandler.ERROR_TEMPORARILY_UNAVAILABLE);

        // Then nothing changed in inventory, cost, repricing ledger, audit, or outbox
        LotBalance lotAfter = queries.balances(shop, sku, false, scope).get(0);
        assertThat(lotAfter.unitCost()).isEqualByComparingTo("100.0000");
        assertThat(lotAfter.qtyOnHand()).isEqualByComparingTo("10.000");
        assertThat(lotAfter.condition()).isEqualTo("GOOD");
        assertThat(avgCostOf(MPCS, sku)).isEqualByComparingTo("100.0000");
        assertThat(count("select count(*) from inventory.stock_movement where location_id = ?", shop))
                .isEqualTo(movementsBefore);
        assertThat(count("select count(*) from inventory.stock_lot where location_id = ?", shop))
                .isEqualTo(lotsBefore);
        assertThat(count("select count(*) from inventory.stock_repricing where location_id = ?", shop))
                .isEqualTo(repricingsBefore);
        assertThat(count("select count(*) from kernel.event_outbox")).isEqualTo(outboxBefore);
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void blockedConditionDowngradeCreatesNoMovementTargetLotCostChangeAuditOrOutbox() {
        UUID sku = fixture.sku(MPCS, "SKU_REPRICE_DOWNGRADE");
        UUID batch = fixture.batch(sku, MPCS, "BAT_DOWN", EXPIRY);
        ScopeContext scope = at(MPCS, shop);

        // Given 10 units at Rs 100 in GOOD condition
        postReceipt(shop, batch, new BigDecimal("10.000"), new BigDecimal("100.0000"), MPCS);
        kernel.reset();
        LotBalance lotBefore = queries.balances(shop, sku, false, scope).get(0);
        int movementsBefore = count("select count(*) from inventory.stock_movement where location_id = ?", shop);
        int outboxBefore = count("select count(*) from kernel.event_outbox");

        // When attempting partial repricing with condition change to DAMAGED
        assertThatThrownBy(() -> repriceHandler.handle(
                        new RepriceStockLot(
                                lotBefore.stockLotId(),
                                new BigDecimal("3.000"),
                                new BigDecimal("50.0000"),
                                LotCondition.DAMAGED,
                                "Water damage markdown"),
                        scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage(RepriceStockLotHandler.ERROR_TEMPORARILY_UNAVAILABLE);

        // Then only the original GOOD lot remains untouched and ledger invariants hold
        List<LotBalance> lotsAfter = queries.balances(shop, sku, false, scope);
        assertThat(lotsAfter).hasSize(1);
        assertThat(lotsAfter.get(0).condition()).isEqualTo("GOOD");
        assertThat(lotsAfter.get(0).qtyOnHand()).isEqualByComparingTo("10.000");
        assertThat(lotsAfter.get(0).unitCost()).isEqualByComparingTo("100.0000");
        assertThat(avgCostOf(MPCS, sku)).isEqualByComparingTo("100.0000");
        assertThat(count("select count(*) from inventory.stock_movement where location_id = ?", shop))
                .isEqualTo(movementsBefore);
        assertThat(count("select count(*) from inventory.stock_repricing where location_id = ?", shop))
                .isZero();
        assertThat(count("select count(*) from kernel.event_outbox")).isEqualTo(outboxBefore);
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void existingRepricingHistoryRemainsReadableWhileMutationsAreBlocked() {
        UUID sku = fixture.sku(MPCS, "SKU_REPRICE_HISTORY");
        UUID batch = fixture.batch(sku, MPCS, "BAT_HIST", EXPIRY);
        ScopeContext scope = at(MPCS, shop);

        postReceipt(shop, batch, new BigDecimal("10.000"), new BigDecimal("100.0000"), MPCS);
        LotBalance lot = queries.balances(shop, sku, false, scope).get(0);
        UUID historicalRepricingId = Ids.next();

        superuserJdbc()
                .update(
                        """
                        insert into inventory.stock_repricing
                            (repricing_id, owner_entity_id, location_id, stock_lot_id, sku_id, batch_id,
                             condition, qty_affected, previous_unit_cost, new_unit_cost, target_condition,
                             target_lot_id, value_adjustment, reason, repriced_by, repriced_at)
                        values (?, ?, ?, ?, ?, ?, 'GOOD', 4.000, 100.0000, 80.0000, 'GOOD',
                                null, -80.00, 'Historical markdown', ?, now())
                        """,
                        historicalRepricingId,
                        MPCS,
                        shop,
                        lot.stockLotId(),
                        sku,
                        batch,
                        scope.userId());
        kernel.reset();

        // Blocked new mutation does not disturb historical records
        assertThatThrownBy(() -> repriceHandler.handle(
                        new RepriceStockLot(
                                lot.stockLotId(),
                                new BigDecimal("2.000"),
                                new BigDecimal("60.0000"),
                                LotCondition.GOOD,
                                "Blocked attempt"),
                        scope))
                .isInstanceOf(ProblemException.class)
                .hasMessage(RepriceStockLotHandler.ERROR_TEMPORARILY_UNAVAILABLE);

        List<StockRepricingView> history = queries.repricings(shop, sku, scope);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).repricingId()).isEqualTo(historicalRepricingId);
        assertThat(history.get(0).reason()).isEqualTo("Historical markdown");
        assertThat(queries.repricings(shop, sku, at(OTHER_ENTITY, otherShop))).isEmpty();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void crossTenantAttemptIsAlsoBlockedWithoutSideEffects() {
        UUID sku = fixture.sku(MPCS, "SKU_REPRICE_TENANT");
        UUID batch = fixture.batch(sku, MPCS, "BAT_TENANT", EXPIRY);
        ScopeContext scope = at(MPCS, shop);

        postReceipt(shop, batch, new BigDecimal("5.000"), new BigDecimal("50.0000"), MPCS);
        kernel.reset();
        LotBalance lot = queries.balances(shop, sku, false, scope).get(0);

        ScopeContext otherScope = at(OTHER_ENTITY, otherShop);

        assertThatThrownBy(() -> repriceHandler.handle(
                        new RepriceStockLot(
                                lot.stockLotId(),
                                new BigDecimal("2.000"),
                                new BigDecimal("40.0000"),
                                LotCondition.GOOD,
                                "cross-tenant attempt"),
                        otherScope))
                .isInstanceOf(ProblemException.class)
                .hasMessage(RepriceStockLotHandler.ERROR_TEMPORARILY_UNAVAILABLE);

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
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

    @Test
    void directExecutionOfApplyLotRepriceWithoutAuditIsRefused() {
        UUID sku = fixture.sku(MPCS, "SKU_DIRECT_REPRICE");
        UUID batch = fixture.batch(sku, MPCS, "BAT_DIR", EXPIRY);
        ScopeContext scope = at(MPCS, shop);

        postReceipt(shop, batch, new BigDecimal("10.000"), new BigDecimal("100.0000"), MPCS);
        kernel.reset();
        LotBalance lot = queries.balances(shop, sku, false, scope).get(0);

        // Attempting to directly call inventory.apply_lot_reprice without an audit row in the current transaction
        org.springframework.jdbc.core.JdbcTemplate jdbc = superuserJdbc();
        assertThatThrownBy(() -> jdbc.execute(String.format(
                        "select set_config('app.scope_class', 'OWN', true), "
                                + "set_config('app.scope_entity_id', '%s', true), "
                                + "set_config('app.scope_location_id', '%s', true), "
                                + "set_config('app.user_id', '%s', true), "
                                + "inventory.apply_lot_reprice(gen_random_uuid(), '%s'::uuid, 50.0000)",
                        MPCS, shop, scope.userId(), lot.stockLotId())))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .satisfies(e -> assertThat(((org.springframework.dao.DataAccessException) e)
                                .getMostSpecificCause()
                                .getMessage())
                        .contains("m5.reprice.audit_required"));
    }

    private void postReceipt(UUID loc, UUID itemBatch, BigDecimal qty, BigDecimal unitCost, UUID entity) {
        ScopeContext scope = at(entity, loc);
        Movement m = new Movement(loc, itemBatch, LotCondition.GOOD, MovementType.RECEIPT, qty, unitCost, null);
        outer.run(scope, () -> ledger.post(new PostMovements(Ids.next(), null, null, List.of(m)), scope));
    }

    private int count(String sql, Object... args) {
        Integer value = superuserJdbc().queryForObject(sql, Integer.class, args);
        return value != null ? value : 0;
    }

    private BigDecimal avgCostOf(UUID entityId, UUID skuId) {
        return superuserJdbc()
                .queryForObject(
                        "select avg_cost from inventory.entity_sku_cost where owner_entity_id = ? and sku_id = ?",
                        BigDecimal.class,
                        entityId,
                        skuId);
    }
}
