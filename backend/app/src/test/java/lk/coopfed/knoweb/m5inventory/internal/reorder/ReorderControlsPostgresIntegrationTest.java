package lk.coopfed.knoweb.m5inventory.internal.reorder;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.ConfigureReorderControl;
import lk.coopfed.knoweb.m5inventory.api.DeleteReorderControl;
import lk.coopfed.knoweb.m5inventory.api.EvaluateLowStock;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.LowStockDetected;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.ReorderControlConfigured;
import lk.coopfed.knoweb.m5inventory.api.ReorderControlDeleted;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.StockMoved;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.ReorderControlView;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for reorder controls and low-stock detection (PGL-M2-01).
 * Tests cover configuration, guards, equality/boundary evaluation, deduplication,
 * replenishment re-arming, event publishing, scheduled job review, and tenant isolation.
 */
class ReorderControlsPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e67d-0000-7000-8000-000000000002");
    private static final UUID OTHER_ENTITY = UUID.fromString("0190e67d-0000-7000-8000-000000000003");
    private static final LocalDate EXPIRY = LocalDate.of(2027, 12, 31);

    @Autowired
    Handles<ConfigureReorderControl, UUID> configureHandler;

    @Autowired
    Handles<DeleteReorderControl, Void> deleteHandler;

    @Autowired
    Handles<EvaluateLowStock, LowStockEvaluationResult> evaluateHandler;

    @Autowired
    LowStockStockMovedConsumer consumer;

    @Autowired
    LowStockReviewJob reviewJob;

    @Autowired
    InventoryQueries inventoryQueries;

    @Autowired
    StockLedger ledger;

    @Autowired
    OuterCommand outer;

    private InventoryFixture fixture;
    private UUID shop1;
    private UUID shop2;
    private UUID otherShop;

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        fixture.entity(MPCS, "M5RC", "MPCS");
        fixture.entity(OTHER_ENTITY, "M5OT", "MPCS");
        shop1 = fixture.location(MPCS, "SHOP");
        shop2 = fixture.location(MPCS, "SHOP");
        otherShop = fixture.location(OTHER_ENTITY, "SHOP");
        kernel.reset();
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void configureReorderControlInsertsAndUpdates() {
        UUID sku = fixture.sku(MPCS, "SKU_CONF_1");
        UUID batch = fixture.batch(sku, MPCS, "B-CONF-1", EXPIRY);
        ScopeContext scope = at(MPCS, shop1);

        // Post 100 stock so available is well above threshold 10
        postReceipt(shop1, batch, new BigDecimal("100.000"), MPCS);
        kernel.reset();

        // 1. Initial configuration (CREATE)
        ConfigureReorderControl cmd = new ConfigureReorderControl(
                shop1, sku, new BigDecimal("10.000"), new BigDecimal("25.000"), new BigDecimal("50.000"));

        UUID controlId = configureHandler.handle(cmd, scope);
        assertThat(controlId).isNotNull();

        assertThat(audits(ConfigureReorderControlHandler.AUDIT_CONFIGURED))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.subject().type()).isEqualTo("reorder_control");
                    assertThat(a.subject().id()).isEqualTo(controlId);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> after = (Map<String, Object>) a.after();
                    assertThat(after.get("reorderLevel")).isEqualTo(new BigDecimal("10.000"));
                    assertThat(after.get("reorderQty")).isEqualTo(new BigDecimal("25.000"));
                    assertThat(after.get("maxQty")).isEqualTo(new BigDecimal("50.000"));
                });

        assertThat(events(ReorderControlConfigured.class)).singleElement().satisfies(e -> {
            assertThat(e.reorderControlId()).isEqualTo(controlId);
            assertThat(e.locationId()).isEqualTo(shop1);
            assertThat(e.skuId()).isEqualTo(sku);
            assertThat(e.reorderLevel()).isEqualByComparingTo("10.000");
            assertThat(e.reorderQty()).isEqualByComparingTo("25.000");
            assertThat(e.maxQty()).isEqualByComparingTo("50.000");
        });

        List<ReorderControlView> views = inventoryQueries.reorderControls(shop1, scope);
        assertThat(views).singleElement().satisfies(v -> {
            assertThat(v.reorderControlId()).isEqualTo(controlId);
            assertThat(v.skuId()).isEqualTo(sku);
            assertThat(v.reorderLevel()).isEqualByComparingTo("10.000");
            assertThat(v.reorderQty()).isEqualByComparingTo("25.000");
            assertThat(v.maxQty()).isEqualByComparingTo("50.000");
            assertThat(v.alertTriggered()).isFalse();
        });

        kernel.reset();

        // 2. Update existing configuration (UPDATE)
        ConfigureReorderControl updateCmd = new ConfigureReorderControl(
                shop1, sku, new BigDecimal("15.000"), new BigDecimal("30.000"), new BigDecimal("60.000"));

        UUID updatedId = configureHandler.handle(updateCmd, scope);
        assertThat(updatedId).isEqualTo(controlId);

        assertThat(audits(ConfigureReorderControlHandler.AUDIT_CONFIGURED))
                .singleElement()
                .satisfies(a -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> after = (Map<String, Object>) a.after();
                    assertThat(after.get("reorderLevel")).isEqualTo(new BigDecimal("15.000"));
                });

        List<ReorderControlView> updatedViews = inventoryQueries.reorderControls(shop1, scope);
        assertThat(updatedViews).singleElement().satisfies(v -> {
            assertThat(v.reorderLevel()).isEqualByComparingTo("15.000");
            assertThat(v.reorderQty()).isEqualByComparingTo("30.000");
            assertThat(v.maxQty()).isEqualByComparingTo("60.000");
        });
    }

    @Test
    void configureReorderControlWhenStockIsLowEmitsInitialAlert() {
        UUID sku = fixture.sku(MPCS, "SKU_CONF_LOW");
        UUID batch = fixture.batch(sku, MPCS, "B-CONF-LOW", EXPIRY);
        ScopeContext scope = at(MPCS, shop1);

        // Stock = 5.000 (strictly below reorder level 10.000)
        postReceipt(shop1, batch, new BigDecimal("5.000"), MPCS);
        kernel.reset();

        ConfigureReorderControl cmd = new ConfigureReorderControl(
                shop1, sku, new BigDecimal("10.000"), new BigDecimal("20.000"), new BigDecimal("50.000"));

        UUID controlId = configureHandler.handle(cmd, scope);
        assertThat(controlId).isNotNull();

        assertThat(audits(ConfigureReorderControlHandler.AUDIT_CONFIGURED)).hasSize(1);
        assertThat(audits(LowStockEvaluationHandler.AUDIT_LOW_STOCK_DETECTED)).hasSize(1);
        assertThat(events(ReorderControlConfigured.class)).hasSize(1);
        assertThat(events(LowStockDetected.class)).singleElement().satisfies(e -> {
            assertThat(e.reorderControlId()).isEqualTo(controlId);
            assertThat(e.locationId()).isEqualTo(shop1);
            assertThat(e.skuId()).isEqualTo(sku);
            assertThat(e.availableQty()).isEqualByComparingTo("5.000");
            assertThat(e.reorderLevel()).isEqualByComparingTo("10.000");
        });

        List<ReorderControlView> views = inventoryQueries.reorderControls(shop1, scope);
        assertThat(views).singleElement().satisfies(v -> {
            assertThat(v.alertTriggered()).isTrue();
            assertThat(v.lastAlertedAt()).isNotNull();
            assertThat(v.currentAvailableQty()).isEqualByComparingTo("5.000");
        });
    }

    @Test
    void validationGuardsRejectInvalidInputs() {
        UUID sku = fixture.sku(MPCS, "SKU_VALIDATE");
        ScopeContext scope = at(MPCS, shop1);

        // Negative reorder level
        assertThatThrownBy(() -> configureHandler.handle(
                        new ConfigureReorderControl(
                                shop1, sku, new BigDecimal("-1.000"), new BigDecimal("10.000"), null),
                        scope))
                .isInstanceOf(ProblemException.class);

        // Zero / negative reorder qty
        assertThatThrownBy(() -> configureHandler.handle(
                        new ConfigureReorderControl(shop1, sku, new BigDecimal("10.000"), BigDecimal.ZERO, null),
                        scope))
                .isInstanceOf(ProblemException.class);

        // Max qty less than reorder level
        assertThatThrownBy(() -> configureHandler.handle(
                        new ConfigureReorderControl(
                                shop1,
                                sku,
                                new BigDecimal("20.000"),
                                new BigDecimal("10.000"),
                                new BigDecimal("15.000")),
                        scope))
                .isInstanceOf(ProblemException.class);

        // SKU does not exist
        UUID nonExistentSku = UUID.randomUUID();
        assertThatThrownBy(() -> configureHandler.handle(
                        new ConfigureReorderControl(
                                shop1, nonExistentSku, new BigDecimal("10.000"), new BigDecimal("10.000"), null),
                        scope))
                .isInstanceOf(ProblemException.class);

        // Location of another entity
        assertThatThrownBy(() -> configureHandler.handle(
                        new ConfigureReorderControl(
                                otherShop, sku, new BigDecimal("10.000"), new BigDecimal("10.000"), null),
                        scope))
                .isInstanceOf(ProblemException.class);
    }

    @Test
    void lowStockEvaluationOnExactEqualityTriggersAlert() {
        UUID sku = fixture.sku(MPCS, "SKU_EQUAL");
        UUID batch = fixture.batch(sku, MPCS, "B-EQ", EXPIRY);
        ScopeContext scope = at(MPCS, shop1);

        // Initial adequate stock = 30.000
        postReceipt(shop1, batch, new BigDecimal("30.000"), MPCS);

        // Configure: reorderLevel = 10.000 (currently healthy 30 > 10, alertTriggered = false)
        configureHandler.handle(
                new ConfigureReorderControl(shop1, sku, new BigDecimal("10.000"), new BigDecimal("20.000"), null),
                scope);

        // Stock drops to exactly 10.000 (equal to reorder level)
        postSale(shop1, batch, "-20.000", MPCS);
        kernel.reset();

        LowStockEvaluationResult result = evaluateHandler.handle(new EvaluateLowStock(shop1, sku), scope);
        assertThat(result.evaluatedCount()).isEqualTo(1);
        assertThat(result.alertsTriggeredCount()).isEqualTo(1);

        assertThat(audits(LowStockEvaluationHandler.AUDIT_LOW_STOCK_DETECTED))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.subject().type()).isEqualTo("reorder_control");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> after = (Map<String, Object>) a.after();
                    assertThat(after.get("locationId")).isEqualTo(shop1);
                    assertThat(after.get("skuId")).isEqualTo(sku);
                    assertThat(after.get("availableQty")).isEqualTo(new BigDecimal("10.000"));
                    assertThat(after.get("reorderLevel")).isEqualTo(new BigDecimal("10.000"));
                });

        assertThat(events(LowStockDetected.class)).singleElement().satisfies(e -> {
            assertThat(e.locationId()).isEqualTo(shop1);
            assertThat(e.skuId()).isEqualTo(sku);
            assertThat(e.availableQty()).isEqualByComparingTo("10.000");
            assertThat(e.reorderLevel()).isEqualByComparingTo("10.000");
            assertThat(e.reorderQty()).isEqualByComparingTo("20.000");
        });

        // alert_triggered flag in view is now true
        List<ReorderControlView> views = inventoryQueries.reorderControls(shop1, scope);
        assertThat(views).singleElement().satisfies(v -> {
            assertThat(v.alertTriggered()).isTrue();
            assertThat(v.lastAlertedAt()).isNotNull();
            assertThat(v.currentAvailableQty()).isEqualByComparingTo("10.000");
        });
    }

    @Test
    void lowStockEvaluationBelowThresholdTriggersAlert() {
        UUID sku = fixture.sku(MPCS, "SKU_BELOW");
        UUID batch = fixture.batch(sku, MPCS, "B-BELOW", EXPIRY);
        ScopeContext scope = at(MPCS, shop1);

        // Initial adequate stock = 30.000
        postReceipt(shop1, batch, new BigDecimal("30.000"), MPCS);

        // Configure: reorderLevel = 15.000 (healthy 30 > 15, alertTriggered = false)
        configureHandler.handle(
                new ConfigureReorderControl(shop1, sku, new BigDecimal("15.000"), new BigDecimal("30.000"), null),
                scope);

        // Stock drops to 5.000 (strictly below 15.000)
        postSale(shop1, batch, "-25.000", MPCS);
        kernel.reset();

        LowStockEvaluationResult result = evaluateHandler.handle(new EvaluateLowStock(shop1, sku), scope);
        assertThat(result.evaluatedCount()).isEqualTo(1);
        assertThat(result.alertsTriggeredCount()).isEqualTo(1);

        assertThat(events(LowStockDetected.class)).hasSize(1);
    }

    @Test
    void deduplicationPreventsDuplicateAlertsWhileLow() {
        UUID sku = fixture.sku(MPCS, "SKU_DEDUP");
        UUID batch = fixture.batch(sku, MPCS, "B-DEDUP", EXPIRY);
        ScopeContext scope = at(MPCS, shop1);

        // Initial adequate stock = 20.000
        postReceipt(shop1, batch, new BigDecimal("20.000"), MPCS);

        // Configure: reorderLevel = 10.000 (healthy 20 > 10, alertTriggered = false)
        configureHandler.handle(
                new ConfigureReorderControl(shop1, sku, new BigDecimal("10.000"), new BigDecimal("20.000"), null),
                scope);

        // 1. Initial breach: sell 15.000 -> stock drops to 5.000
        postSale(shop1, batch, "-15.000", MPCS);
        kernel.reset();

        LowStockEvaluationResult first = evaluateHandler.handle(new EvaluateLowStock(shop1, sku), scope);
        assertThat(first.alertsTriggeredCount()).isEqualTo(1);
        assertThat(events(LowStockDetected.class)).hasSize(1);

        kernel.reset();

        // 2. Further sale: stock drops to 3.000 (still <= 10.000)
        postSale(shop1, batch, "-2.000", MPCS);
        kernel.reset();

        LowStockEvaluationResult second = evaluateHandler.handle(new EvaluateLowStock(shop1, sku), scope);
        assertThat(second.evaluatedCount()).isEqualTo(1);
        assertThat(second.alertsTriggeredCount()).isEqualTo(0);

        // No new audit or event committed
        assertThat(audits(LowStockEvaluationHandler.AUDIT_LOW_STOCK_DETECTED)).isEmpty();
        assertThat(events(LowStockDetected.class)).isEmpty();
    }

    @Test
    void replenishmentRearmsAlertTriggeredAndSubsequentBreachFires() {
        UUID sku = fixture.sku(MPCS, "SKU_REARM");
        UUID batch = fixture.batch(sku, MPCS, "B-REARM", EXPIRY);
        ScopeContext scope = at(MPCS, shop1);

        // Initial adequate stock = 20.000
        postReceipt(shop1, batch, new BigDecimal("20.000"), MPCS);

        // Configure: reorderLevel = 10.000 (healthy 20 > 10, alertTriggered = false)
        configureHandler.handle(
                new ConfigureReorderControl(shop1, sku, new BigDecimal("10.000"), new BigDecimal("20.000"), null),
                scope);

        // 1. Initial breach: sell 15.000 -> stock drops to 5.000
        postSale(shop1, batch, "-15.000", MPCS);
        kernel.reset();

        LowStockEvaluationResult initialBreach = evaluateHandler.handle(new EvaluateLowStock(shop1, sku), scope);
        assertThat(initialBreach.alertsTriggeredCount()).isEqualTo(1);
        assertThat(events(LowStockDetected.class)).hasSize(1);
        kernel.reset();

        // 2. Replenishment: add 20.000 -> stock = 25.000 (> 10.000 reorder level)
        postReceipt(shop1, batch, new BigDecimal("20.000"), MPCS);
        LowStockEvaluationResult rearmResult = evaluateHandler.handle(new EvaluateLowStock(shop1, sku), scope);
        assertThat(rearmResult.alertsTriggeredCount()).isEqualTo(0);

        // Check alert_triggered has reset to false
        List<ReorderControlView> views = inventoryQueries.reorderControls(shop1, scope);
        assertThat(views).singleElement().satisfies(v -> {
            assertThat(v.alertTriggered()).isFalse();
            assertThat(v.currentAvailableQty()).isEqualByComparingTo("25.000");
        });

        kernel.reset();

        // 3. Subsequent breach: sell 18.000 -> stock = 7.000 (<= 10.000)
        postSale(shop1, batch, "-18.000", MPCS);
        LowStockEvaluationResult secondBreach = evaluateHandler.handle(new EvaluateLowStock(shop1, sku), scope);
        assertThat(secondBreach.alertsTriggeredCount()).isEqualTo(1);

        assertThat(events(LowStockDetected.class)).hasSize(1);
        assertThat(audits(LowStockEvaluationHandler.AUDIT_LOW_STOCK_DETECTED)).hasSize(1);
    }

    @Test
    void stockMovedConsumerTriggersEvaluationOnStockChange() {
        UUID sku = fixture.sku(MPCS, "SKU_CONSUMER");
        UUID batch = fixture.batch(sku, MPCS, "B-CONS", EXPIRY);
        ScopeContext scope = at(MPCS, shop1);

        // Initially 20.000 stock
        postReceipt(shop1, batch, new BigDecimal("20.000"), MPCS);

        // Configure: reorderLevel = 15.000 (healthy 20 > 15, alertTriggered = false)
        configureHandler.handle(
                new ConfigureReorderControl(shop1, sku, new BigDecimal("15.000"), new BigDecimal("20.000"), null),
                scope);

        // Stock movement drops available stock to 12.000 (<= 15.000 threshold breach)
        postSale(shop1, batch, "-8.000", MPCS);
        kernel.reset();

        // 1. Test EventConsumer dispatching with JsonNode payload (supporting canonical camelCase)
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node =
                mapper.createObjectNode().put("locationId", shop1.toString()).put("skuId", sku.toString());

        consumer.onStockMoved(node, scope);

        assertThat(events(LowStockDetected.class)).singleElement().satisfies(e -> {
            assertThat(e.locationId()).isEqualTo(shop1);
            assertThat(e.skuId()).isEqualTo(sku);
            assertThat(e.availableQty()).isEqualByComparingTo("12.000"); // from DB state
        });

        kernel.reset();

        // 2. Test fallback snake_case in JsonNode payload
        ObjectNode snakeNode =
                mapper.createObjectNode().put("location_id", shop1.toString()).put("sku_id", sku.toString());

        consumer.onStockMoved(snakeNode, scope);

        // Alert already triggered, so deduplicated (no new event)
        assertThat(events(LowStockDetected.class)).isEmpty();

        // 3. Test typed domain event overload
        StockMoved moved = new StockMoved(
                Ids.next(),
                MPCS,
                shop1,
                Ids.next(),
                batch,
                sku,
                LotCondition.GOOD.name(),
                MovementType.SALE.name(),
                new BigDecimal("-4.000"),
                new BigDecimal("100.0000"),
                new BigDecimal("8.000"),
                null,
                "SALE",
                1L);

        consumer.onStockMoved(moved, scope);
        assertThat(events(LowStockDetected.class)).isEmpty();
    }

    @Test
    void lowStockReviewJobEvaluatesAllEntitiesAndLocations() {
        UUID sku1 = fixture.sku(MPCS, "SKU_JOB_1");
        UUID sku2 = fixture.sku(MPCS, "SKU_JOB_2");
        ScopeContext scope = at(MPCS, shop1);

        // Initial adequate stock = 20.000 for sku1
        UUID batch1 = fixture.batch(sku1, MPCS, "B-JOB-1", EXPIRY);
        postReceipt(shop1, batch1, new BigDecimal("20.000"), MPCS);

        // Configure sku1: level 10 (healthy 20 > 10, alertTriggered = false)
        configureHandler.handle(
                new ConfigureReorderControl(shop1, sku1, new BigDecimal("10.000"), new BigDecimal("20.000"), null),
                scope);

        // Sell 15.000 -> sku1 available stock is now 5.000 (breached <= 10.000, not yet alerted)
        postSale(shop1, batch1, "-15.000", MPCS);

        // High stock: level 10, stock 30 -> adequate
        UUID batch2 = fixture.batch(sku2, MPCS, "B-JOB-2", EXPIRY);
        postReceipt(shop1, batch2, new BigDecimal("30.000"), MPCS);
        configureHandler.handle(
                new ConfigureReorderControl(shop1, sku2, new BigDecimal("10.000"), new BigDecimal("20.000"), null),
                scope);

        kernel.reset();

        int alerts = reviewJob.review(execution());
        assertThat(alerts).isEqualTo(1);

        assertThat(events(LowStockDetected.class)).singleElement().satisfies(e -> assertThat(e.skuId())
                .isEqualTo(sku1));
    }

    @Test
    void deleteReorderControlRemovesRecord() {
        UUID sku = fixture.sku(MPCS, "SKU_DELETE");
        ScopeContext scope = at(MPCS, shop1);

        UUID controlId = configureHandler.handle(
                new ConfigureReorderControl(shop1, sku, new BigDecimal("10.000"), new BigDecimal("20.000"), null),
                scope);
        assertThat(inventoryQueries.reorderControls(shop1, scope)).hasSize(1);

        kernel.reset();

        // 1. Delete logically retires the control
        deleteHandler.handle(new DeleteReorderControl(shop1, sku), scope);

        assertThat(audits(DeleteReorderControlHandler.AUDIT_DELETED))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.subject().type()).isEqualTo("reorder_control");
                    assertThat(a.subject().id()).isEqualTo(controlId);
                });

        assertThat(events(ReorderControlDeleted.class)).singleElement().satisfies(e -> {
            assertThat(e.reorderControlId()).isEqualTo(controlId);
            assertThat(e.ownerEntityId()).isEqualTo(MPCS);
            assertThat(e.locationId()).isEqualTo(shop1);
            assertThat(e.skuId()).isEqualTo(sku);
        });

        // Active query returns empty
        assertThat(inventoryQueries.reorderControls(shop1, scope)).isEmpty();

        // Database row physically retained with status RETIRED (no application DELETE)
        String status = superuserJdbc()
                .queryForObject(
                        "select status from inventory.reorder_control where reorder_control_id = ?",
                        String.class,
                        controlId);
        assertThat(status).isEqualTo("RETIRED");

        // 2. Deleting an already-retired control throws ProblemException (m5.reorder.not_found)
        assertThatThrownBy(() -> deleteHandler.handle(new DeleteReorderControl(shop1, sku), scope))
                .isInstanceOf(ProblemException.class);

        // 3. Re-configuring the control reactivates the record
        kernel.reset();
        UUID reactivatedId = configureHandler.handle(
                new ConfigureReorderControl(shop1, sku, new BigDecimal("12.000"), new BigDecimal("25.000"), null),
                scope);
        assertThat(reactivatedId).isEqualTo(controlId);
        assertThat(inventoryQueries.reorderControls(shop1, scope)).hasSize(1);
    }

    @Test
    void deleteNonExistentReorderControlThrowsAndPublishesNothing() {
        UUID sku = fixture.sku(MPCS, "SKU_DEL_NOT_FOUND");
        ScopeContext scope = at(MPCS, shop1);

        assertThatThrownBy(() -> deleteHandler.handle(new DeleteReorderControl(shop1, sku), scope))
                .isInstanceOf(ProblemException.class);

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void unauthorizedEvaluationCausesNoSideEffects() {
        UUID sku = fixture.sku(MPCS, "SKU_UNAUTH_EVAL");
        ScopeContext mpcsScope = at(MPCS, shop1);
        ScopeContext otherScope = at(OTHER_ENTITY, otherShop);

        configureHandler.handle(
                new ConfigureReorderControl(shop1, sku, new BigDecimal("10.000"), new BigDecimal("20.000"), null),
                mpcsScope);
        kernel.reset();

        // Other entity attempts to evaluate shop1 -> rejected because location belongs to MPCS
        assertThatThrownBy(() -> evaluateHandler.handle(new EvaluateLowStock(shop1, sku), otherScope))
                .isInstanceOf(ProblemException.class);

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void rlsEnforcesTenantIsolation() {
        UUID skuMpcs = fixture.sku(MPCS, "SKU_RLS_MPCS");
        ScopeContext mpcsScope = at(MPCS, shop1);
        ScopeContext otherScope = at(OTHER_ENTITY, otherShop);

        configureHandler.handle(
                new ConfigureReorderControl(shop1, skuMpcs, new BigDecimal("10.000"), new BigDecimal("20.000"), null),
                mpcsScope);

        // Other entity queries shop1 -> isolated by RLS, returns empty
        List<ReorderControlView> otherViews = inventoryQueries.reorderControls(shop1, otherScope);
        assertThat(otherViews).isEmpty();

        // Other entity attempts to delete shop1's reorder control -> not found
        assertThatThrownBy(() -> deleteHandler.handle(new DeleteReorderControl(shop1, skuMpcs), otherScope))
                .isInstanceOf(ProblemException.class);
    }

    private void postReceipt(UUID location, UUID batch, BigDecimal qty, UUID entity) {
        ScopeContext scope = at(entity, location);
        Movement m = new Movement(
                location, batch, LotCondition.GOOD, MovementType.RECEIPT, qty, new BigDecimal("100.0000"), null);
        outer.run(scope, () -> ledger.post(new PostMovements(Ids.next(), null, null, List.of(m)), scope));
    }

    private void postSale(UUID location, UUID batch, String qty, UUID entity) {
        ScopeContext scope = at(entity, location);
        Movement m =
                new Movement(location, batch, LotCondition.GOOD, MovementType.SALE, new BigDecimal(qty), null, null);
        outer.run(scope, () -> ledger.post(new PostMovements(Ids.next(), null, null, List.of(m)), scope));
    }

    private static JobExecution execution() {
        return new JobExecution() {
            @Override
            public UUID runId() {
                return UUID.randomUUID();
            }

            @Override
            public void itemsProcessed(int count) {}

            @Override
            public Optional<ScopeContext> systemScope() {
                return Optional.empty();
            }
        };
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
