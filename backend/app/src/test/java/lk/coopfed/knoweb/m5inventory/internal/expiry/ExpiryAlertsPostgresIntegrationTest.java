package lk.coopfed.knoweb.m5inventory.internal.expiry;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry.ConfigScope;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.EvaluateExpiryAlerts;
import lk.coopfed.knoweb.m5inventory.api.ExpiryAlertScanResult;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.NearExpiryDetected;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.SlowMovingDetected;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.NearExpiryLotView;
import lk.coopfed.knoweb.m5inventory.query.SlowMovingStockView;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for near-expiry view, alert feed, and deterministic slow-moving stock detection (PGL-M2-07).
 */
class ExpiryAlertsPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e67e-0000-7000-8000-000000000002");
    private static final UUID OTHER_ENTITY = UUID.fromString("0190e67e-0000-7000-8000-000000000003");
    private static final String SLOW_MOVING_DAYS = "inventory.slow_moving_days";
    private static final String SLOW_MOVING_THRESHOLD_DOS = "inventory.slow_moving_threshold_dos";

    @Autowired
    Handles<EvaluateExpiryAlerts, ExpiryAlertScanResult> evaluateAlertsHandler;

    @Autowired
    InventoryQueries inventoryQueries;

    @Autowired
    StockLedger ledger;

    @Autowired
    OuterCommand outer;

    @Autowired
    Clock clock;

    @Autowired
    ExpiryAlertReviewJob reviewJob;

    @Autowired
    ConfigRegistry config;

    private InventoryFixture fixture;
    private UUID shop1;
    private UUID otherShop;
    private LocalDate today;
    private boolean slowMovingOverrideSet;

    @BeforeEach
    void setUp() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        fixture.entity(MPCS, "M5AL", "MPCS");
        fixture.entity(OTHER_ENTITY, "M5OT", "MPCS");
        shop1 = fixture.location(MPCS, "SHOP");
        otherShop = fixture.location(OTHER_ENTITY, "SHOP");
        today = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Colombo"));
        kernel.reset();
    }

    @AfterEach
    void tearDown() {
        if (slowMovingOverrideSet) {
            ScopeContext scope = own(OTHER_ENTITY);
            outer.run(scope, () -> {
                config.set(SLOW_MOVING_DAYS, ConfigScope.entity(OTHER_ENTITY), "30", scope, "test cleanup");
                config.set(SLOW_MOVING_THRESHOLD_DOS, ConfigScope.entity(OTHER_ENTITY), "90", scope, "test cleanup");
                return null;
            });
            superuserJdbc()
                    .update(
                            "delete from kernel.config_value where key in (?, ?) and scope_entity_id = ?",
                            SLOW_MOVING_DAYS,
                            SLOW_MOVING_THRESHOLD_DOS,
                            OTHER_ENTITY);
        }
        fixture.clean();
    }

    @Test
    void nearExpiryViewCategorizesBatchesByUrgencyTiers() {
        ScopeContext scope = own(MPCS);

        // 1. Critical batch: 4 days to expiry (<= 7)
        UUID sku1 = fixture.sku(MPCS, "MILK_CRITICAL");
        UUID batch1 = fixture.batch(sku1, MPCS, "B-CRIT", today.plusDays(4));
        postGood(shop1, batch1, "20.000", "120.00", MPCS);

        // 2. Warning batch: 20 days to expiry (<= 30)
        UUID sku2 = fixture.sku(MPCS, "BREAD_WARNING");
        UUID batch2 = fixture.batch(sku2, MPCS, "B-WARN", today.plusDays(20));
        postGood(shop1, batch2, "15.000", "80.00", MPCS);

        // 3. Approaching batch: 50 days to expiry (31-60 under the current resolver)
        UUID sku3 = fixture.sku(MPCS, "BISCUIT_APPR");
        UUID batch3 = fixture.batch(sku3, MPCS, "B-APPR", today.plusDays(50));
        postGood(shop1, batch3, "30.000", "150.00", MPCS);

        // 4. Expired batch: -2 days
        UUID sku4 = fixture.sku(MPCS, "CURD_EXPIRED");
        UUID batch4 = fixture.batch(sku4, MPCS, "B-EXP", today.minusDays(2));
        postGood(shop1, batch4, "5.000", "60.00", MPCS);

        List<NearExpiryLotView> allLots = inventoryQueries.nearExpiryLots(shop1, 90, null, scope);
        assertThat(allLots).hasSize(4);

        NearExpiryLotView crit = allLots.stream()
                .filter(l -> l.batchId().equals(batch1))
                .findFirst()
                .orElseThrow();
        assertThat(crit.urgencyTier()).isEqualTo("CRITICAL");
        assertThat(crit.daysToExpiry()).isEqualTo(4);

        NearExpiryLotView warn = allLots.stream()
                .filter(l -> l.batchId().equals(batch2))
                .findFirst()
                .orElseThrow();
        assertThat(warn.urgencyTier()).isEqualTo("WARNING");
        assertThat(warn.daysToExpiry()).isEqualTo(20);

        NearExpiryLotView appr = allLots.stream()
                .filter(l -> l.batchId().equals(batch3))
                .findFirst()
                .orElseThrow();
        assertThat(appr.urgencyTier()).isEqualTo("APPROACHING");
        assertThat(appr.daysToExpiry()).isEqualTo(50);

        NearExpiryLotView exp = allLots.stream()
                .filter(l -> l.batchId().equals(batch4))
                .findFirst()
                .orElseThrow();
        assertThat(exp.urgencyTier()).isEqualTo("EXPIRED");
        assertThat(exp.daysToExpiry()).isEqualTo(-2);

        // Filter by CRITICAL tier only
        List<NearExpiryLotView> criticalOnly = inventoryQueries.nearExpiryLots(shop1, 90, "CRITICAL", scope);
        assertThat(criticalOnly).hasSize(1);
        assertThat(criticalOnly.get(0).batchId()).isEqualTo(batch1);
    }

    @Test
    void slowMovingStockDetectionClassifiesStagnantAndSlowVelocity() {
        ScopeContext scope = own(MPCS);

        // Item 1: Stock on hand = 50, zero sales -> STAGNANT
        UUID skuStagnant = fixture.sku(MPCS, "STAGNANT_ITEM");
        UUID batchStagnant = fixture.batch(skuStagnant, MPCS, "B-STAG", today.plusDays(180));
        postGood(shop1, batchStagnant, "50.000", "200.00", MPCS);

        // Item 2: Stock on hand = 100, 10 units sold in 30 days -> DOS = 100 / (10/30) = 300 days (> 90) ->
        // SLOW_VELOCITY
        UUID skuSlow = fixture.sku(MPCS, "SLOW_ITEM");
        UUID batchSlow = fixture.batch(skuSlow, MPCS, "B-SLOW", today.plusDays(180));
        postGood(shop1, batchSlow, "110.000", "100.00", MPCS);
        postSale(shop1, batchSlow, "10.000", MPCS);

        // Item 3: Stock on hand = 20, 40 units sold in 30 days -> DOS = 20 / (40/30) = 15 days (healthy, <= 90)
        UUID skuHealthy = fixture.sku(MPCS, "FAST_ITEM");
        UUID batchHealthy = fixture.batch(skuHealthy, MPCS, "B-FAST", today.plusDays(180));
        postGood(shop1, batchHealthy, "60.000", "50.00", MPCS);
        postSale(shop1, batchHealthy, "40.000", MPCS);

        List<SlowMovingStockView> slowItems = inventoryQueries.slowMovingStock(shop1, 30, 90, scope);
        assertThat(slowItems).hasSize(2);

        SlowMovingStockView stagnantView = slowItems.stream()
                .filter(i -> i.skuId().equals(skuStagnant))
                .findFirst()
                .orElseThrow();
        assertThat(stagnantView.classification()).isEqualTo("STAGNANT");
        assertThat(stagnantView.salesInWindow()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(stagnantView.daysOfSupply()).isEqualByComparingTo("999.00");

        SlowMovingStockView slowView = slowItems.stream()
                .filter(i -> i.skuId().equals(skuSlow))
                .findFirst()
                .orElseThrow();
        assertThat(slowView.classification()).isEqualTo("SLOW_VELOCITY");
        assertThat(slowView.salesInWindow()).isEqualByComparingTo("10.000");
        assertThat(slowView.daysOfSupply()).isEqualByComparingTo("300.00");
    }

    @Test
    void slowMovingQueryUsesEntityScopedEffectiveConfigurationWhenParametersAreOmitted() {
        ScopeContext scope = own(OTHER_ENTITY);
        UUID sku = fixture.sku(OTHER_ENTITY, "CONFIGURED_SLOW_ITEM");
        UUID batch = fixture.batch(sku, OTHER_ENTITY, "B-CONFIG-SLOW", today.plusDays(180));
        postGood(otherShop, batch, "110.000", "100.00", OTHER_ENTITY);
        postSale(otherShop, batch, "10.000", OTHER_ENTITY);

        assertThat(inventoryQueries.slowMovingStock(otherShop, 30, 90, scope))
                .extracting(SlowMovingStockView::skuId)
                .containsExactly(sku);

        outer.run(scope, () -> {
            config.set(SLOW_MOVING_DAYS, ConfigScope.entity(OTHER_ENTITY), "10", scope, "test override");
            config.set(SLOW_MOVING_THRESHOLD_DOS, ConfigScope.entity(OTHER_ENTITY), "200", scope, "test override");
            return null;
        });
        slowMovingOverrideSet = true;

        assertThat(inventoryQueries.slowMovingStock(otherShop, null, null, scope))
                .isEmpty();
    }

    @Test
    void alertScanEvaluatesAndDeduplicatesRepeatedScans() {
        ScopeContext scope = own(MPCS);

        UUID sku = fixture.sku(MPCS, "SCAN_MILK");
        UUID batch = fixture.batch(sku, MPCS, "B-SCAN", today.plusDays(5)); // Critical
        postGood(shop1, batch, "60.000", "150.00", MPCS);
        postSale(shop1, batch, "30.000", MPCS);

        UUID skuStag = fixture.sku(MPCS, "SCAN_STAG");
        UUID batchStag = fixture.batch(skuStag, MPCS, "B-SSTAG", today.plusDays(120));
        postGood(shop1, batchStag, "40.000", "90.00", MPCS);
        kernel.reset();

        // First scan: emits 1 near-expiry alert and 1 slow-moving alert
        var scanStartedAt = clock.instant();
        ExpiryAlertScanResult firstResult = evaluateAlertsHandler.handle(new EvaluateExpiryAlerts(shop1), scope);
        var scanFinishedAt = clock.instant();
        assertThat(firstResult.nearExpiryAlertsEmitted()).isEqualTo(1);
        assertThat(firstResult.slowMovingAlertsEmitted()).isEqualTo(1);

        assertThat(audits(EvaluateExpiryAlertsHandler.AUDIT_NEAR_EXPIRY)).hasSize(1);
        assertThat(audits(EvaluateExpiryAlertsHandler.AUDIT_SLOW_MOVING)).hasSize(1);
        assertThat(events(NearExpiryDetected.class)).hasSize(1);
        assertThat(events(SlowMovingDetected.class)).hasSize(1);

        NearExpiryDetected nearEvent = events(NearExpiryDetected.class).get(0);
        assertThat(nearEvent.alertId()).isNotNull();
        assertThat(nearEvent.ownerEntityId()).isEqualTo(MPCS);
        assertThat(nearEvent.locationId()).isEqualTo(shop1);
        assertThat(nearEvent.batchId()).isEqualTo(batch);
        assertThat(nearEvent.batchNo()).startsWith("B-SCAN");
        assertThat(nearEvent.skuId()).isEqualTo(sku);
        assertThat(nearEvent.expiryDate()).isEqualTo(today.plusDays(5));
        assertThat(nearEvent.urgencyTier()).isEqualTo("CRITICAL");
        assertThat(nearEvent.daysToExpiry()).isEqualTo(5);
        assertThat(nearEvent.qtyOnHand()).isEqualByComparingTo("30.000");
        assertThat(nearEvent.detectedAt()).isBetween(scanStartedAt, scanFinishedAt);

        KernelRecorder.AuditRecord nearAudit =
                audits(EvaluateExpiryAlertsHandler.AUDIT_NEAR_EXPIRY).get(0);
        assertThat(nearAudit.subject().type()).isEqualTo("location");
        assertThat(nearAudit.subject().id()).isEqualTo(shop1);
        assertThat(nearAudit.before()).isNull();
        Map<?, ?> nearAfter = (Map<?, ?>) nearAudit.after();
        assertThat(nearAfter.get("skuId")).isEqualTo(sku.toString());
        assertThat(nearAfter.get("batchId")).isEqualTo(batch.toString());
        assertThat(nearAfter.get("tier")).isEqualTo("CRITICAL");
        assertThat(nearAfter.get("daysToExpiry")).isEqualTo("5");
        assertThat(nearAfter.get("qtyOnHand")).isEqualTo("30.000");
        assertThat(nearAudit.scope()).isEqualTo(scope);

        SlowMovingDetected slowEvent = events(SlowMovingDetected.class).get(0);
        assertThat(slowEvent.alertId()).isNotNull();
        assertThat(slowEvent.ownerEntityId()).isEqualTo(MPCS);
        assertThat(slowEvent.locationId()).isEqualTo(shop1);
        assertThat(slowEvent.skuId()).isEqualTo(skuStag);
        assertThat(slowEvent.qtyOnHand()).isEqualByComparingTo("40.000");
        assertThat(slowEvent.daysWithoutSale()).isEqualTo(30);
        assertThat(slowEvent.lastSaleDate()).isNull();
        assertThat(slowEvent.salesInWindow()).isEqualByComparingTo("0.000");
        assertThat(slowEvent.averageDailySales()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(slowEvent.daysOfSupply()).isEqualByComparingTo("999.0");
        assertThat(slowEvent.classification()).isEqualTo("STAGNANT");
        assertThat(slowEvent.detectedAt()).isBetween(scanStartedAt, scanFinishedAt);

        KernelRecorder.AuditRecord slowAudit =
                audits(EvaluateExpiryAlertsHandler.AUDIT_SLOW_MOVING).get(0);
        assertThat(slowAudit.subject().type()).isEqualTo("location");
        assertThat(slowAudit.subject().id()).isEqualTo(shop1);
        assertThat(slowAudit.before()).isNull();
        Map<?, ?> slowAfter = (Map<?, ?>) slowAudit.after();
        assertThat(slowAfter.get("skuId")).isEqualTo(skuStag.toString());
        assertThat(slowAfter.get("classification")).isEqualTo("STAGNANT");
        assertThat(slowAfter.get("daysWithoutSale")).isEqualTo("30");
        assertThat(slowAfter.get("daysOfSupply")).isEqualTo("999.0");
        assertThat(slowAfter.get("qtyOnHand")).isEqualTo("40.000");
        assertThat(slowAudit.scope()).isEqualTo(scope);

        kernel.reset();

        // Second scan immediately after: deduplication prevents re-alerting!
        ExpiryAlertScanResult secondResult = evaluateAlertsHandler.handle(new EvaluateExpiryAlerts(shop1), scope);
        assertThat(secondResult.nearExpiryAlertsEmitted()).isEqualTo(0);
        assertThat(secondResult.slowMovingAlertsEmitted()).isEqualTo(0);

        assertThat(events(NearExpiryDetected.class)).isEmpty();
        assertThat(events(SlowMovingDetected.class)).isEmpty();
    }

    @Test
    void nearExpiryEvaluationUsesOnlyGoodStockAndSkipsExpiredStock() {
        ScopeContext scope = own(MPCS);

        UUID goodSku = fixture.sku(MPCS, "ELIGIBLE_GOOD");
        UUID goodBatch = fixture.batch(goodSku, MPCS, "B-GOOD", today.plusDays(5));
        postGood(shop1, goodBatch, "20.000", "100.00", MPCS);
        postSale(shop1, goodBatch, "10.000", MPCS);

        UUID damagedSku = fixture.sku(MPCS, "DAMAGED_ONLY");
        UUID damagedBatch = fixture.batch(damagedSku, MPCS, "B-DAMAGED", today.plusDays(5));
        postMovement(shop1, damagedBatch, LotCondition.DAMAGED, MovementType.RECEIPT, "20.000", "100.00", MPCS);

        UUID mixedSku = fixture.sku(MPCS, "MIXED_CONDITION");
        UUID mixedBatch = fixture.batch(mixedSku, MPCS, "B-MIXED", today.plusDays(5));
        postGood(shop1, mixedBatch, "20.000", "100.00", MPCS);
        postSale(shop1, mixedBatch, "10.000", MPCS);
        postMovement(shop1, mixedBatch, LotCondition.DAMAGED, MovementType.RECEIPT, "5.000", "100.00", MPCS);

        UUID expiredSku = fixture.sku(MPCS, "EXPIRED_GOOD");
        UUID expiredBatch = fixture.batch(expiredSku, MPCS, "B-EXPIRED", today.minusDays(1));
        postGood(shop1, expiredBatch, "20.000", "100.00", MPCS);
        postSale(shop1, expiredBatch, "10.000", MPCS);

        List<NearExpiryLotView> visible = inventoryQueries.nearExpiryLots(shop1, 30, null, scope);
        assertThat(visible)
                .extracting(NearExpiryLotView::batchId)
                .containsExactlyInAnyOrder(goodBatch, mixedBatch, expiredBatch);
        assertThat(visible).extracting(NearExpiryLotView::batchId).doesNotContain(damagedBatch);
        assertThat(visible.stream().filter(lot -> lot.batchId().equals(mixedBatch)))
                .hasSize(1);
        assertThat(visible.stream().filter(lot -> lot.batchId().equals(expiredBatch)))
                .singleElement()
                .satisfies(lot -> assertThat(lot.urgencyTier()).isEqualTo("EXPIRED"));

        kernel.reset();
        ExpiryAlertScanResult result = evaluateAlertsHandler.handle(new EvaluateExpiryAlerts(shop1), scope);

        assertThat(result.nearExpiryAlertsEmitted()).isEqualTo(2);
        assertThat(events(NearExpiryDetected.class))
                .extracting(NearExpiryDetected::batchId)
                .containsExactlyInAnyOrder(goodBatch, mixedBatch)
                .doesNotContain(damagedBatch, expiredBatch);
        assertThat(audits(EvaluateExpiryAlertsHandler.AUDIT_NEAR_EXPIRY)).hasSize(2);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.stock_alert_state where alert_type = 'NEAR_EXPIRY'",
                                Integer.class))
                .isEqualTo(2);
    }

    @Test
    void nearExpiryAlertResolvesWhenGoodStockDisappearsButDamagedStockRemains() {
        ScopeContext scope = own(MPCS);
        UUID sku = fixture.sku(MPCS, "RESOLVE_GOOD_ONLY");
        UUID batch = fixture.batch(sku, MPCS, "B-RESOLVE", today.plusDays(5));
        postGood(shop1, batch, "20.000", "100.00", MPCS);
        postMovement(shop1, batch, LotCondition.DAMAGED, MovementType.RECEIPT, "5.000", "100.00", MPCS);

        assertThat(evaluateAlertsHandler
                        .handle(new EvaluateExpiryAlerts(shop1), scope)
                        .nearExpiryAlertsEmitted())
                .isEqualTo(1);

        postMovement(shop1, batch, LotCondition.GOOD, MovementType.WRITE_OFF, "-20.000", "100.00", MPCS);
        kernel.reset();

        assertThat(evaluateAlertsHandler
                        .handle(new EvaluateExpiryAlerts(shop1), scope)
                        .nearExpiryAlertsEmitted())
                .isZero();
        assertThat(events(NearExpiryDetected.class)).isEmpty();
        assertThat(audits(EvaluateExpiryAlertsHandler.AUDIT_NEAR_EXPIRY)).isEmpty();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select status from inventory.stock_alert_state where location_id = ? and batch_id = ? and alert_type = 'NEAR_EXPIRY'",
                                String.class,
                                shop1,
                                batch))
                .isEqualTo("RESOLVED");
    }

    @Test
    void slowMovingExcludesExactThresholdNonPositiveAndDamagedStock() {
        ScopeContext scope = own(MPCS);

        UUID exactSku = fixture.sku(MPCS, "DOS_EXACT_90");
        UUID exactBatch = fixture.batch(exactSku, MPCS, "B-DOS-90", today.plusDays(180));
        postGood(shop1, exactBatch, "40.000", "100.00", MPCS);
        postSale(shop1, exactBatch, "10.000", MPCS);

        UUID zeroSku = fixture.sku(MPCS, "ZERO_STOCK");
        UUID zeroBatch = fixture.batch(zeroSku, MPCS, "B-ZERO", today.plusDays(180));
        postGood(shop1, zeroBatch, "10.000", "100.00", MPCS);
        postSale(shop1, zeroBatch, "10.000", MPCS);

        UUID negativeSku = fixture.sku(MPCS, "NEGATIVE_STOCK");
        UUID negativeBatch = fixture.batch(negativeSku, MPCS, "B-NEGATIVE", today.plusDays(180));
        postMovement(shop1, negativeBatch, LotCondition.GOOD, MovementType.SALE, "-5.000", "100.00", MPCS);

        UUID damagedSku = fixture.sku(MPCS, "DAMAGED_STOCK");
        UUID damagedBatch = fixture.batch(damagedSku, MPCS, "B-DAMAGED-SLOW", today.plusDays(180));
        postMovement(shop1, damagedBatch, LotCondition.DAMAGED, MovementType.RECEIPT, "50.000", "100.00", MPCS);

        assertThat(inventoryQueries.slowMovingStock(shop1, 30, 90, scope)).isEmpty();
    }

    @Test
    void urgencyTierBoundariesMatchTheCurrentResolver() {
        assertThat(ExpiryAlertStore.resolveTier(-1, 7, 30)).isEqualTo("EXPIRED");
        assertThat(ExpiryAlertStore.resolveTier(0, 7, 30)).isEqualTo("CRITICAL");
        assertThat(ExpiryAlertStore.resolveTier(7, 7, 30)).isEqualTo("CRITICAL");
        assertThat(ExpiryAlertStore.resolveTier(8, 7, 30)).isEqualTo("WARNING");
        assertThat(ExpiryAlertStore.resolveTier(30, 7, 30)).isEqualTo("WARNING");
        assertThat(ExpiryAlertStore.resolveTier(31, 7, 30)).isEqualTo("APPROACHING");
        assertThat(ExpiryAlertStore.resolveTier(60, 7, 30)).isEqualTo("APPROACHING");
        assertThat(ExpiryAlertStore.resolveTier(61, 7, 30)).isEqualTo("HEALTHY");
    }

    @Test
    void scheduledReviewScansActiveStockLocationsWithoutExpiryTasksAndKeepsEntitiesIsolated() {
        UUID activeMpcsShop = fixture.location(MPCS, "SHOP", "ACTIVE");
        UUID activeOtherWarehouse = fixture.location(OTHER_ENTITY, "WAREHOUSE", "ACTIVE");
        UUID dormantMpcsShop = fixture.location(MPCS, "SHOP", "DORMANT");
        UUID activeOffice = fixture.location(MPCS, "OFFICE", "ACTIVE");

        UUID mpcsSku = fixture.sku(MPCS, "JOB_MPCS");
        UUID mpcsBatch = fixture.batch(mpcsSku, MPCS, "B-JOB-MPCS", today.plusDays(5));
        postGood(activeMpcsShop, mpcsBatch, "20.000", "100.00", MPCS);
        postSale(activeMpcsShop, mpcsBatch, "10.000", MPCS);

        UUID otherSku = fixture.sku(OTHER_ENTITY, "JOB_OTHER");
        UUID otherBatch = fixture.batch(otherSku, OTHER_ENTITY, "B-JOB-OTHER", today.plusDays(5));
        postGood(activeOtherWarehouse, otherBatch, "20.000", "100.00", OTHER_ENTITY);
        postSale(activeOtherWarehouse, otherBatch, "10.000", OTHER_ENTITY);

        UUID dormantSku = fixture.sku(MPCS, "JOB_DORMANT");
        UUID dormantBatch = fixture.batch(dormantSku, MPCS, "B-JOB-DORMANT", today.plusDays(5));
        postGood(dormantMpcsShop, dormantBatch, "20.000", "100.00", MPCS);
        postSale(dormantMpcsShop, dormantBatch, "10.000", MPCS);

        UUID officeSku = fixture.sku(MPCS, "JOB_OFFICE");
        UUID officeBatch = fixture.batch(officeSku, MPCS, "B-JOB-OFFICE", today.plusDays(5));
        postGood(activeOffice, officeBatch, "20.000", "100.00", MPCS);
        postSale(activeOffice, officeBatch, "10.000", MPCS);

        assertThat(superuserJdbc().queryForObject("select count(*) from inventory.expiry_task", Integer.class))
                .isZero();
        kernel.reset();

        assertThat(reviewJob.review(execution())).isEqualTo(2);
        assertThat(events(NearExpiryDetected.class)).hasSize(2);
        assertThat(events(NearExpiryDetected.class).stream().map(NearExpiryDetected::locationId))
                .containsExactlyInAnyOrder(activeMpcsShop, activeOtherWarehouse)
                .doesNotContain(dormantMpcsShop, activeOffice);
        assertThat(audits(EvaluateExpiryAlertsHandler.AUDIT_NEAR_EXPIRY).stream()
                        .map(a -> a.scope().entityId()))
                .containsExactlyInAnyOrder(MPCS, OTHER_ENTITY);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.stock_alert_state where location_id in (?, ?)",
                                Integer.class,
                                activeMpcsShop,
                                activeOtherWarehouse))
                .isEqualTo(2);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.stock_alert_state where location_id in (?, ?)",
                                Integer.class,
                                dormantMpcsShop,
                                activeOffice))
                .isZero();

        kernel.reset();
        assertThat(reviewJob.review(execution())).isZero();
        assertThat(events(NearExpiryDetected.class)).isEmpty();
        assertThat(events(SlowMovingDetected.class)).isEmpty();
    }

    @Test
    void rejectsCallerOutsideEntityScope() {
        ScopeContext otherScope = own(OTHER_ENTITY);

        assertThatThrownBy(() -> evaluateAlertsHandler.handle(new EvaluateExpiryAlerts(shop1), otherScope))
                .isInstanceOf(ProblemException.class);
    }

    private void postGood(UUID location, UUID batch, String qty, String cost, UUID entity) {
        postMovement(location, batch, LotCondition.GOOD, MovementType.RECEIPT, qty, cost, entity);
    }

    private void postMovement(
            UUID location,
            UUID batch,
            LotCondition condition,
            MovementType movementType,
            String qty,
            String cost,
            UUID entity) {
        ScopeContext scope = own(entity);
        outer.run(
                scope,
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(new Movement(
                                        location,
                                        batch,
                                        condition,
                                        movementType,
                                        new BigDecimal(qty),
                                        new BigDecimal(cost),
                                        null))),
                        scope));
    }

    private void postSale(UUID location, UUID batch, String qty, UUID entity) {
        ScopeContext scope = own(entity);
        outer.run(
                scope,
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(new Movement(
                                        location,
                                        batch,
                                        LotCondition.GOOD,
                                        MovementType.SALE,
                                        new BigDecimal(qty).negate(),
                                        new BigDecimal("100.00"),
                                        null))),
                        scope));
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
}
