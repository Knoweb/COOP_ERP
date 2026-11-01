package lk.coopfed.knoweb.m5inventory.internal.expiry;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
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

    private InventoryFixture fixture;
    private UUID shop1;
    private UUID otherShop;
    private LocalDate today;

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

        // 3. Approaching batch: 50 days to expiry (<= 90)
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
    void alertScanEvaluatesAndDeduplicatesRepeatedScans() {
        ScopeContext scope = own(MPCS);

        UUID sku = fixture.sku(MPCS, "SCAN_MILK");
        UUID batch = fixture.batch(sku, MPCS, "B-SCAN", today.plusDays(5)); // Critical
        postGood(shop1, batch, "30.000", "150.00", MPCS);

        UUID skuStag = fixture.sku(MPCS, "SCAN_STAG");
        UUID batchStag = fixture.batch(skuStag, MPCS, "B-SSTAG", today.plusDays(120));
        postGood(shop1, batchStag, "40.000", "90.00", MPCS);
        kernel.reset();

        // First scan: emits 1 near-expiry alert and 1 slow-moving alert
        ExpiryAlertScanResult firstResult = evaluateAlertsHandler.handle(new EvaluateExpiryAlerts(shop1), scope);
        assertThat(firstResult.nearExpiryAlertsEmitted()).isEqualTo(1);
        assertThat(firstResult.slowMovingAlertsEmitted()).isEqualTo(1);

        assertThat(audits(EvaluateExpiryAlertsHandler.AUDIT_NEAR_EXPIRY)).hasSize(1);
        assertThat(audits(EvaluateExpiryAlertsHandler.AUDIT_SLOW_MOVING)).hasSize(1);
        assertThat(events(NearExpiryDetected.class)).hasSize(1);
        assertThat(events(SlowMovingDetected.class)).hasSize(1);

        NearExpiryDetected nearEvent = events(NearExpiryDetected.class).get(0);
        assertThat(nearEvent.batchId()).isEqualTo(batch);
        assertThat(nearEvent.urgencyTier()).isEqualTo("CRITICAL");
        assertThat(nearEvent.daysToExpiry()).isEqualTo(5);

        SlowMovingDetected slowEvent = events(SlowMovingDetected.class).get(0);
        assertThat(slowEvent.skuId()).isEqualTo(skuStag);
        assertThat(slowEvent.classification()).isEqualTo("STAGNANT");

        kernel.reset();

        // Second scan immediately after: deduplication prevents re-alerting!
        ExpiryAlertScanResult secondResult = evaluateAlertsHandler.handle(new EvaluateExpiryAlerts(shop1), scope);
        assertThat(secondResult.nearExpiryAlertsEmitted()).isEqualTo(0);
        assertThat(secondResult.slowMovingAlertsEmitted()).isEqualTo(0);

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
                                        MovementType.RECEIPT,
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
}
