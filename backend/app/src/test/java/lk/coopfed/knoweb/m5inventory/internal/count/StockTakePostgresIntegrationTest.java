package lk.coopfed.knoweb.m5inventory.internal.count;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.at;
import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.BatchStockTakeScan;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.OpenStockTakeSession;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.ReconcileStockTakeSession;
import lk.coopfed.knoweb.m5inventory.api.RecordStockTakeScan;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.StockTakeBatchScanned;
import lk.coopfed.knoweb.m5inventory.api.StockTakeScanRecorded;
import lk.coopfed.knoweb.m5inventory.api.StockTakeSessionOpened;
import lk.coopfed.knoweb.m5inventory.api.StockTakeSessionReconciled;
import lk.coopfed.knoweb.m5inventory.query.CountView;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.StockControlQueries;
import lk.coopfed.knoweb.m5inventory.query.StockTakeBatchScanResult;
import lk.coopfed.knoweb.m5inventory.query.StockTakeQueries;
import lk.coopfed.knoweb.m5inventory.query.StockTakeReviewView;
import lk.coopfed.knoweb.m5inventory.query.StockTakeScanResult;
import lk.coopfed.knoweb.m5inventory.query.StockTakeScanView;
import lk.coopfed.knoweb.m5inventory.query.StockTakeSessionView;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Mobile Stock-Taking Postgres Integration Tests (PGL-M2-09).
 * Verifies session opening, barcode/SKU scanning with strict idempotency,
 * offline batch sync, review variances, and reconciliation workflows.
 */
class StockTakePostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e67c-0000-7000-8000-000000000002");

    @Autowired
    OpenStockTakeSessionHandler openSession;

    @Autowired
    RecordStockTakeScanHandler recordScan;

    @Autowired
    BatchStockTakeScanHandler batchScan;

    @Autowired
    ReconcileStockTakeSessionHandler reconcileSession;

    @Autowired
    StockTakeQueries stockTakeQueries;

    @Autowired
    StockControlQueries controlQueries;

    @Autowired
    InventoryQueries inventoryQueries;

    @Autowired
    StockLedger ledger;

    @Autowired
    OuterCommand outer;

    private InventoryFixture fixture;
    private UUID stores;
    private UUID rice;
    private UUID riceBatch;
    private UUID dhal;
    private UUID dhalBatch;

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        superuserJdbc().update("delete from catalogue.sku_barcode where owner_entity_id = ?", MPCS);

        fixture.entity(MPCS, "M5CT", "MPCS");
        stores = fixture.location(MPCS, "WAREHOUSE");
        rice = fixture.sku(MPCS, "RICE5");
        riceBatch = fixture.batch(rice, MPCS, "R1", LocalDate.of(2027, 3, 31));
        dhal = fixture.sku(MPCS, "DHAL1");
        dhalBatch = fixture.batch(dhal, MPCS, "D1", LocalDate.of(2027, 5, 31));

        post(MovementType.RECEIPT, riceBatch, "50", "100");
        post(MovementType.RECEIPT, dhalBatch, "10", "300");

        kernel.reset();
    }

    @AfterEach
    void clean() {
        superuserJdbc().update("delete from catalogue.sku_barcode where owner_entity_id = ?", MPCS);
        fixture.clean();
    }

    private void linkBarcode(String barcode, UUID skuId, UUID batchId) {
        superuserJdbc()
                .update(
                        """
                insert into catalogue.sku_barcode
                    (barcode, symbology, sku_id, uom_code, batch_id, owner_entity_id, status)
                values (?, 'INTERNAL', ?, 'EA', ?, ?, 'ACTIVE')
                """,
                        barcode,
                        skuId,
                        batchId,
                        MPCS);
    }

    private void post(MovementType type, UUID batch, String qty, String cost) {
        ScopeContext scope = own(MPCS);
        outer.run(
                scope,
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                Instant.now(),
                                null,
                                List.of(new Movement(
                                        stores,
                                        batch,
                                        LotCondition.GOOD,
                                        type,
                                        new BigDecimal(qty),
                                        cost == null ? null : new BigDecimal(cost),
                                        null))),
                        scope));
    }

    private BigDecimal onHand(UUID batch) {
        return inventoryQueries.balances(stores, null, true, own(MPCS)).stream()
                .filter(l -> l.batchId().equals(batch))
                .findFirst()
                .orElseThrow()
                .qtyOnHand();
    }

    @SuppressWarnings("unchecked")
    private <T extends DomainEvent> List<T> events(Class<T> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(e -> (T) e)
                .toList();
    }

    @Test
    @DisplayName("Open stock take session creates counting task and records audit and domain events")
    void openStockTakeSessionSuccess() {
        ScopeContext scope = at(MPCS, stores);
        UUID sessionId = openSession.handle(new OpenStockTakeSession(stores, "FULL", null), scope);

        assertThat(sessionId).isNotNull();

        // Query active session
        var activeOpt = stockTakeQueries.activeSession(stores, scope);
        assertThat(activeOpt).isPresent();
        StockTakeSessionView session = activeOpt.get();
        assertThat(session.sessionId()).isEqualTo(sessionId);
        assertThat(session.locationId()).isEqualTo(stores);
        assertThat(session.scopeKind()).isEqualTo("FULL");
        assertThat(session.status()).isEqualTo("COUNTING");
        assertThat(session.expectedLotsCount()).isEqualTo(2);

        // Verify audit and event
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("STOCK_TAKE_SESSION_OPENED");

        assertThat(events(StockTakeSessionOpened.class)).singleElement().satisfies(e -> {
            assertThat(e.sessionId()).isEqualTo(sessionId);
            assertThat(e.locationId()).isEqualTo(stores);
            assertThat(e.expectedLotsCount()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("Record scan with barcode resolves lot, deduplicates retries, and detects conflicts")
    void recordScanWithBarcodeAndIdempotency() {
        ScopeContext scope = at(MPCS, stores);
        UUID sessionId = openSession.handle(new OpenStockTakeSession(stores, "FULL", null), scope);
        linkBarcode("479000100001", rice, riceBatch);
        kernel.reset();

        UUID scanId = Ids.next();
        RecordStockTakeScan scan = new RecordStockTakeScan(
                sessionId,
                scanId,
                "479000100001",
                null,
                null,
                null,
                LotCondition.GOOD,
                new BigDecimal("5.000"),
                null,
                "mob-01",
                "shelf-A");

        StockTakeScanResult result = recordScan.handle(scan, scope);

        assertThat(result.scanId()).isEqualTo(scanId);
        assertThat(result.wasDuplicate()).isFalse();
        assertThat(result.skuId()).isEqualTo(rice);
        assertThat(result.batchId()).isEqualTo(riceBatch);
        assertThat(result.quantity()).isEqualByComparingTo("5.000");
        assertThat(result.totalCountedForLot()).isEqualByComparingTo("5.000");

        // Verify audit & event
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("STOCK_TAKE_SCAN_RECORDED");
        assertThat(events(StockTakeScanRecorded.class)).singleElement().satisfies(e -> {
            assertThat(e.scanId()).isEqualTo(scanId);
            assertThat(e.quantity()).isEqualByComparingTo("5.000");
        });

        // 1. Identical Retry -> Returns duplicate without inserting or auditing again
        kernel.reset();
        StockTakeScanResult retryResult = recordScan.handle(scan, scope);
        assertThat(retryResult.wasDuplicate()).isTrue();
        assertThat(retryResult.totalCountedForLot()).isEqualByComparingTo("5.000");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        // 2. Conflicting Retry -> Same scanId but different quantity -> throws conflict
        RecordStockTakeScan conflict = new RecordStockTakeScan(
                sessionId,
                scanId,
                "479000100001",
                null,
                null,
                null,
                LotCondition.GOOD,
                new BigDecimal("10.000"),
                null,
                "mob-01",
                null);
        assertThatThrownBy(() -> recordScan.handle(conflict, scope))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m5.stock_take.scan_conflict");

        // 3. Conflicting Condition -> Same scanId but DAMAGED condition -> throws conflict
        RecordStockTakeScan conflictCondition = new RecordStockTakeScan(
                sessionId,
                scanId,
                "479000100001",
                null,
                null,
                null,
                LotCondition.DAMAGED,
                new BigDecimal("5.000"),
                null,
                "mob-01",
                null);
        assertThatThrownBy(() -> recordScan.handle(conflictCondition, scope))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m5.stock_take.scan_conflict");

        // Query session scans
        List<StockTakeScanView> scans = stockTakeQueries.listScans(sessionId, scope);
        assertThat(scans).hasSize(1);
        assertThat(scans.get(0).scanId()).isEqualTo(scanId);
        assertThat(scans.get(0).countedQty()).isEqualByComparingTo("5.000");
    }

    @Test
    @DisplayName("Batch scan synchronizes multiple scans and reports applied vs duplicate counts")
    void batchScanSynchronization() {
        ScopeContext scope = at(MPCS, stores);
        UUID sessionId = openSession.handle(new OpenStockTakeSession(stores, "FULL", null), scope);
        linkBarcode("479000100001", rice, riceBatch);
        linkBarcode("479000200002", dhal, dhalBatch);
        kernel.reset();

        UUID scan1 = Ids.next();
        UUID scan2 = Ids.next();

        BatchStockTakeScan batch = new BatchStockTakeScan(
                sessionId,
                List.of(
                        new RecordStockTakeScan(
                                sessionId,
                                scan1,
                                "479000100001",
                                null,
                                null,
                                null,
                                LotCondition.GOOD,
                                new BigDecimal("10.000"),
                                null,
                                "mob-01",
                                null),
                        new RecordStockTakeScan(
                                sessionId,
                                scan2,
                                "479000200002",
                                null,
                                null,
                                null,
                                LotCondition.GOOD,
                                new BigDecimal("5.000"),
                                null,
                                "mob-01",
                                null)));

        StockTakeBatchScanResult result = batchScan.handle(batch, scope);
        assertThat(result.processedCount()).isEqualTo(2);
        assertThat(result.duplicateCount()).isZero();
        assertThat(result.totalScansCount()).isEqualTo(2);

        // Verify audit & event
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("STOCK_TAKE_BATCH_SCANNED");
        assertThat(events(StockTakeBatchScanned.class)).singleElement().satisfies(e -> {
            assertThat(e.processedCount()).isEqualTo(2);
            assertThat(e.duplicateCount()).isZero();
        });

        // Re-send the batch -> all 2 are duplicates
        kernel.reset();
        StockTakeBatchScanResult retry = batchScan.handle(batch, scope);
        assertThat(retry.processedCount()).isEqualTo(2);
        assertThat(retry.duplicateCount()).isEqualTo(2);
        assertThat(retry.totalScansCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Review variances and reconcile within tolerance closes and auto-posts adjustment")
    void reviewAndReconcileWithinTolerance() {
        ScopeContext scope = at(MPCS, stores);
        UUID sessionId = openSession.handle(new OpenStockTakeSession(stores, "FULL", null), scope);
        linkBarcode("479000100001", rice, riceBatch);
        linkBarcode("479000200002", dhal, dhalBatch);

        // Rice book: 50, count: 49 (-1 within 2 unit / 1% tolerance)
        recordScan.handle(
                new RecordStockTakeScan(
                        sessionId,
                        Ids.next(),
                        "479000100001",
                        null,
                        null,
                        null,
                        LotCondition.GOOD,
                        new BigDecimal("49.000"),
                        null,
                        "mob-01",
                        null),
                scope);

        // Dhal book: 10, count: 10 (0 variance)
        recordScan.handle(
                new RecordStockTakeScan(
                        sessionId,
                        Ids.next(),
                        "479000200002",
                        null,
                        null,
                        null,
                        LotCondition.GOOD,
                        new BigDecimal("10.000"),
                        null,
                        "mob-01",
                        null),
                scope);

        // Review variances
        StockTakeReviewView review = stockTakeQueries.getReview(sessionId, scope);
        assertThat(review.lines()).hasSize(2);
        assertThat(review.discrepantLots()).isEqualTo(1); // 1 line has variance (-1 unit)

        kernel.reset();
        UUID taskId = reconcileSession.handle(new ReconcileStockTakeSession(sessionId, "ZERO", null), scope);
        CountView closed = controlQueries.count(taskId, scope).orElseThrow();

        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.outcome()).isEqualTo("POSTED");
        assertThat(onHand(riceBatch)).isEqualByComparingTo("49");

        // Verify audit and event
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains(
                        "COUNT_SUBMITTED", "STOCK_POSTED", "COUNT_TOLERANCE_CLEARED", "STOCK_TAKE_SESSION_RECONCILED");

        assertThat(events(StockTakeSessionReconciled.class)).singleElement().satisfies(e -> {
            assertThat(e.sessionId()).isEqualTo(sessionId);
            assertThat(e.postedCount()).isEqualTo(1);
            assertThat(e.reviewCount()).isZero();
        });
    }

    @Test
    @DisplayName("Reconcile with variance beyond tolerance transitions session to VARIANCE_REVIEW for supervisor")
    void reconcileWithLargeVarianceTransitionsToVarianceReview() {
        ScopeContext scope = at(MPCS, stores);
        UUID sessionId = openSession.handle(new OpenStockTakeSession(stores, "FULL", null), scope);
        linkBarcode("479000100001", rice, riceBatch);

        // Rice book: 50, count: 10 (-40 units short = -Rs 4,000, beyond tolerance)
        recordScan.handle(
                new RecordStockTakeScan(
                        sessionId,
                        Ids.next(),
                        "479000100001",
                        null,
                        null,
                        null,
                        LotCondition.GOOD,
                        new BigDecimal("10.000"),
                        null,
                        "mob-01",
                        null),
                scope);

        kernel.reset();
        UUID taskId = reconcileSession.handle(new ReconcileStockTakeSession(sessionId, "ZERO", null), scope);
        CountView review = controlQueries.count(taskId, scope).orElseThrow();

        assertThat(review.status()).isEqualTo("VARIANCE_REVIEW");
        assertThat(review.reviewValue()).isEqualByComparingTo("4000.00");
        assertThat(review.lines()).hasSize(2); // rice and uncounted dhal (treated as ZERO)
        assertThat(review.lines().stream().anyMatch(l -> !l.withinTolerance())).isTrue();

        // Physical on-hand balance remains unmodified until supervisor approves
        assertThat(onHand(riceBatch)).isEqualByComparingTo("50");

        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("COUNT_SUBMITTED", "STOCK_TAKE_SESSION_RECONCILED");

        assertThat(events(StockTakeSessionReconciled.class)).singleElement().satisfies(e -> {
            assertThat(e.sessionId()).isEqualTo(sessionId);
            assertThat(e.reviewCount()).isGreaterThanOrEqualTo(1);
            assertThat(e.reviewValue()).isGreaterThan(BigDecimal.ZERO);
        });
    }
}
