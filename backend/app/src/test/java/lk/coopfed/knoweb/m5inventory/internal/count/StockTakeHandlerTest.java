package lk.coopfed.knoweb.m5inventory.internal.count;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.query.BarcodeLookup;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchView;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.LookupResult;
import lk.coopfed.knoweb.m2catalogue.query.SkuFilter;
import lk.coopfed.knoweb.m2catalogue.query.SkuPage;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.BatchStockTakeScan;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.OpenStockTakeSession;
import lk.coopfed.knoweb.m5inventory.api.ReconcileStockTakeSession;
import lk.coopfed.knoweb.m5inventory.api.RecordStockTakeScan;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import lk.coopfed.knoweb.m5inventory.query.StockTakeBatchScanResult;
import lk.coopfed.knoweb.m5inventory.query.StockTakeScanResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class StockTakeHandlerTest {

    private static final UUID ENTITY = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final ScopeContext OWN_SCOPE = InventoryFixture.at(ENTITY, LOCATION);
    private static final ScopeContext FED_SCOPE = new ScopeContext(
            USER,
            null,
            null,
            List.of(),
            null,
            PolicyClass.FEDERATION_VIEW,
            Set.of(),
            null,
            Locale.ENGLISH,
            UUID.randomUUID());

    private CountStore countStore;
    private StockTakeStore stockTakeStore;
    private JdbcTemplate jdbc;
    private ControlPolicy policy;
    private CatalogueQueries catalogueQueries;
    private BatchQueries batchQueries;
    private Clock clock;
    private AuditFacade audit;
    private EventPublisher events;

    @BeforeEach
    void setUp() {
        countStore = mock(CountStore.class);
        stockTakeStore = mock(StockTakeStore.class);
        jdbc = mock(JdbcTemplate.class);
        policy = mock(ControlPolicy.class);
        catalogueQueries = mock(CatalogueQueries.class);
        batchQueries = mock(BatchQueries.class);
        clock = Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneId.of("UTC"));
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
    }

    @Nested
    @DisplayName("OpenStockTakeSession")
    class OpenSessionTests {

        private OpenStockTakeSessionHandler handler;

        @BeforeEach
        void init() {
            handler = new OpenStockTakeSessionHandler(
                    countStore, stockTakeStore, catalogueQueries, policy, jdbc, clock, "UTC", audit, events);
        }

        @Test
        void nonOwnScopeThrows() {
            assertThatThrownBy(() -> handler.handle(new OpenStockTakeSession(LOCATION, "FULL", null), FED_SCOPE))
                    .isInstanceOf(ProblemException.class);
        }

        @Test
        void nullLocationThrows() {
            assertThatThrownBy(() -> handler.handle(new OpenStockTakeSession(null, "FULL", null), OWN_SCOPE))
                    .isInstanceOf(ProblemException.class)
                    .hasMessageContaining("m5.location.required");
        }

        @Test
        void activeCountingSessionIsReturnedDirectly() {
            UUID existingTaskId = UUID.randomUUID();
            CountStore.Task existing = new CountStore.Task(
                    existingTaskId, ENTITY, LOCATION, "FULL", List.of(), "COUNTING", null, null, null, clock.instant());
            when(stockTakeStore.activeTask(LOCATION)).thenReturn(Optional.of(existing));

            UUID result = handler.handle(new OpenStockTakeSession(LOCATION, "FULL", null), OWN_SCOPE);

            assertThat(result).isEqualTo(existingTaskId);
            verify(jdbc, never()).update(any(String.class), any(Object[].class));
        }

        @Test
        void scheduledSessionIsResumedIntoCounting() {
            UUID existingTaskId = UUID.randomUUID();
            CountStore.Task existing = new CountStore.Task(
                    existingTaskId, ENTITY, LOCATION, "FULL", List.of(), "SCHEDULED", null, null, null, null);
            when(stockTakeStore.activeTask(LOCATION)).thenReturn(Optional.of(existing));
            when(stockTakeStore.expectedLotsCount(existingTaskId)).thenReturn(12);

            UUID result = handler.handle(new OpenStockTakeSession(LOCATION, "FULL", null), OWN_SCOPE);

            assertThat(result).isEqualTo(existingTaskId);
            verify(audit).record(eq("STOCK_TAKE_SESSION_OPENED"), any(), any(), any(), eq(OWN_SCOPE));
        }

        @Test
        void sessionInVarianceReviewThrows() {
            UUID existingTaskId = UUID.randomUUID();
            CountStore.Task existing = new CountStore.Task(
                    existingTaskId, ENTITY, LOCATION, "FULL", List.of(), "VARIANCE_REVIEW", null, null, null, null);
            when(stockTakeStore.activeTask(LOCATION)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> handler.handle(new OpenStockTakeSession(LOCATION, "FULL", null), OWN_SCOPE))
                    .isInstanceOf(ProblemException.class)
                    .hasMessageContaining("m5.count.in_review");
        }

        @Test
        void noActiveSessionSchedulesAndStartsNewCount() {
            when(stockTakeStore.activeTask(LOCATION)).thenReturn(Optional.empty());
            when(countStore.openAt(LOCATION)).thenReturn(false);
            when(stockTakeStore.expectedLotsCount(any(UUID.class))).thenReturn(25);

            UUID result = handler.handle(new OpenStockTakeSession(LOCATION, "FULL", null), OWN_SCOPE);

            assertThat(result).isNotNull();
            verify(audit).record(eq("STOCK_TAKE_SESSION_OPENED"), any(), any(), any(), eq(OWN_SCOPE));
        }
    }

    @Nested
    @DisplayName("RecordStockTakeScan")
    class RecordScanTests {

        private RecordStockTakeScanHandler handler;
        private UUID taskId;
        private CountStore.Task countingTask;
        private UUID skuId;
        private UUID batchId;

        @BeforeEach
        void init() {
            handler = new RecordStockTakeScanHandler(
                    countStore, stockTakeStore, jdbc, policy, catalogueQueries, batchQueries, clock, audit, events);
            taskId = UUID.randomUUID();
            countingTask = new CountStore.Task(
                    taskId, ENTITY, LOCATION, "FULL", List.of(), "COUNTING", null, null, null, clock.instant());
            when(countStore.lock(taskId)).thenReturn(Optional.of(countingTask));

            skuId = UUID.randomUUID();
            batchId = UUID.randomUUID();
        }

        @Test
        void invalidQuantityThrows() {
            RecordStockTakeScan scan = new RecordStockTakeScan(
                    taskId,
                    UUID.randomUUID(),
                    "12345",
                    null,
                    null,
                    null,
                    LotCondition.GOOD,
                    BigDecimal.ZERO,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> handler.handle(scan, OWN_SCOPE))
                    .isInstanceOf(ProblemException.class)
                    .hasMessageContaining("m5.scan.quantity_invalid");
        }

        @Test
        void notCountingStatusThrows() {
            CountStore.Task closedTask = new CountStore.Task(
                    taskId, ENTITY, LOCATION, "FULL", List.of(), "CLOSED", null, null, null, clock.instant());
            when(countStore.lock(taskId)).thenReturn(Optional.of(closedTask));

            RecordStockTakeScan scan = new RecordStockTakeScan(
                    taskId,
                    UUID.randomUUID(),
                    "12345",
                    null,
                    null,
                    null,
                    LotCondition.GOOD,
                    BigDecimal.ONE,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> handler.handle(scan, OWN_SCOPE))
                    .isInstanceOf(ProblemException.class)
                    .hasMessageContaining("m5.count.not_counting");
        }

        @Test
        void strictIdempotencyRecognizesIdenticalRetryWithoutDoubleCounting() {
            UUID scanId = UUID.randomUUID();
            StockTakeStore.ScanRow existingScan = new StockTakeStore.ScanRow(
                    scanId,
                    taskId,
                    ENTITY,
                    LOCATION,
                    "4790001",
                    skuId,
                    batchId,
                    "GOOD",
                    new BigDecimal("3.000"),
                    clock.instant(),
                    USER,
                    "device-1",
                    null);
            when(stockTakeStore.findScan(scanId)).thenReturn(Optional.of(existingScan));
            when(stockTakeStore.totalCountedForLot(taskId, batchId, "GOOD")).thenReturn(new BigDecimal("3.000"));

            RecordStockTakeScan scan = new RecordStockTakeScan(
                    taskId,
                    scanId,
                    "4790001",
                    skuId,
                    null,
                    batchId,
                    LotCondition.GOOD,
                    new BigDecimal("3.000"),
                    null,
                    "device-1",
                    null);

            StockTakeScanResult result = handler.handle(scan, OWN_SCOPE);

            assertThat(result.wasDuplicate()).isTrue();
            assertThat(result.quantity()).isEqualByComparingTo("3.000");
            assertThat(result.totalCountedForLot()).isEqualByComparingTo("3.000");
            // Must NOT re-insert into DB or re-audit
            verifyNoInteractions(jdbc);
            verify(audit, never()).record(any(), any(), any(), any(), any());
        }

        @Test
        void conflictingRetryWithSameScanIdThrows() {
            UUID scanId = UUID.randomUUID();
            StockTakeStore.ScanRow existingScan = new StockTakeStore.ScanRow(
                    scanId,
                    taskId,
                    ENTITY,
                    LOCATION,
                    "4790001",
                    skuId,
                    batchId,
                    "GOOD",
                    new BigDecimal("3.000"),
                    clock.instant(),
                    USER,
                    "device-1",
                    null);
            when(stockTakeStore.findScan(scanId)).thenReturn(Optional.of(existingScan));

            // Different quantity (5.000 vs 3.000) for same scanId
            RecordStockTakeScan conflicting = new RecordStockTakeScan(
                    taskId,
                    scanId,
                    "4790001",
                    skuId,
                    null,
                    batchId,
                    LotCondition.GOOD,
                    new BigDecimal("5.000"),
                    null,
                    "device-1",
                    null);

            assertThatThrownBy(() -> handler.handle(conflicting, OWN_SCOPE))
                    .isInstanceOf(ProblemException.class)
                    .hasMessageContaining("m5.stock_take.scan_conflict");
        }

        @Test
        void conflictingConditionWithSameScanIdThrows() {
            UUID scanId = UUID.randomUUID();
            StockTakeStore.ScanRow existingScan = new StockTakeStore.ScanRow(
                    scanId,
                    taskId,
                    ENTITY,
                    LOCATION,
                    "4790001",
                    skuId,
                    batchId,
                    "GOOD",
                    new BigDecimal("3.000"),
                    clock.instant(),
                    USER,
                    "device-1",
                    null);
            when(stockTakeStore.findScan(scanId)).thenReturn(Optional.of(existingScan));

            RecordStockTakeScan conflicting = new RecordStockTakeScan(
                    taskId,
                    scanId,
                    "4790001",
                    skuId,
                    null,
                    batchId,
                    LotCondition.DAMAGED,
                    new BigDecimal("3.000"),
                    null,
                    "device-1",
                    null);

            assertThatThrownBy(() -> handler.handle(conflicting, OWN_SCOPE))
                    .isInstanceOf(ProblemException.class)
                    .hasMessageContaining("m5.stock_take.scan_conflict");
        }

        @Test
        void conflictingSkuWithSameScanIdThrows() {
            UUID scanId = UUID.randomUUID();
            UUID otherSkuId = UUID.randomUUID();
            StockTakeStore.ScanRow existingScan = new StockTakeStore.ScanRow(
                    scanId,
                    taskId,
                    ENTITY,
                    LOCATION,
                    "4790001",
                    skuId,
                    batchId,
                    "GOOD",
                    new BigDecimal("3.000"),
                    clock.instant(),
                    USER,
                    "device-1",
                    null);
            when(stockTakeStore.findScan(scanId)).thenReturn(Optional.of(existingScan));

            RecordStockTakeScan conflicting = new RecordStockTakeScan(
                    taskId,
                    scanId,
                    "4790001",
                    otherSkuId,
                    null,
                    batchId,
                    LotCondition.GOOD,
                    new BigDecimal("3.000"),
                    null,
                    "device-1",
                    null);

            assertThatThrownBy(() -> handler.handle(conflicting, OWN_SCOPE))
                    .isInstanceOf(ProblemException.class)
                    .hasMessageContaining("m5.stock_take.scan_conflict");
        }

        @Test
        void conflictingBatchWithSameScanIdThrows() {
            UUID scanId = UUID.randomUUID();
            UUID otherBatchId = UUID.randomUUID();
            StockTakeStore.ScanRow existingScan = new StockTakeStore.ScanRow(
                    scanId,
                    taskId,
                    ENTITY,
                    LOCATION,
                    "4790001",
                    skuId,
                    batchId,
                    "GOOD",
                    new BigDecimal("3.000"),
                    clock.instant(),
                    USER,
                    "device-1",
                    null);
            when(stockTakeStore.findScan(scanId)).thenReturn(Optional.of(existingScan));

            RecordStockTakeScan conflicting = new RecordStockTakeScan(
                    taskId,
                    scanId,
                    "4790001",
                    skuId,
                    null,
                    otherBatchId,
                    LotCondition.GOOD,
                    new BigDecimal("3.000"),
                    null,
                    "device-1",
                    null);

            assertThatThrownBy(() -> handler.handle(conflicting, OWN_SCOPE))
                    .isInstanceOf(ProblemException.class)
                    .hasMessageContaining("m5.stock_take.scan_conflict");
        }

        @Test
        void successfulBarcodeScanResolvesAndPersists() {
            String barcode = "4790123456789";
            UUID scanId = UUID.randomUUID();
            when(stockTakeStore.findScan(scanId)).thenReturn(Optional.empty());

            LookupResult lookup = new LookupResult(
                    skuId,
                    "SKU-001",
                    "Tea 100g",
                    "තේ 100g",
                    "தேநீர் 100g",
                    false,
                    false,
                    "g",
                    BigDecimal.ONE,
                    new LookupResult.BatchRef(batchId, "B-001", null, new BigDecimal("150.00")),
                    null,
                    false,
                    true,
                    false);
            when(catalogueQueries.lookupByBarcode(any(BarcodeLookup.class), eq(OWN_SCOPE)))
                    .thenReturn(Optional.of(lookup));
            when(stockTakeStore.totalCountedForLot(taskId, batchId, "GOOD")).thenReturn(new BigDecimal("2.000"));

            RecordStockTakeScan scan = new RecordStockTakeScan(
                    taskId,
                    scanId,
                    barcode,
                    null,
                    null,
                    null,
                    LotCondition.GOOD,
                    new BigDecimal("2.000"),
                    null,
                    "mob-1",
                    null);

            StockTakeScanResult result = handler.handle(scan, OWN_SCOPE);

            assertThat(result.wasDuplicate()).isFalse();
            assertThat(result.skuCode()).isEqualTo("SKU-001");
            assertThat(result.batchNo()).isEqualTo("B-001");
            assertThat(result.quantity()).isEqualByComparingTo("2.000");

            verify(jdbc)
                    .update(
                            contains("insert into inventory.stock_take_scan"),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any());
            verify(audit).record(eq("STOCK_TAKE_SCAN_RECORDED"), any(), any(), any(), eq(OWN_SCOPE));
            verify(events).publish(any());
        }

        @Test
        void fallbackToSkuCodeWhenBarcodeNotFound() {
            String code = "SKU-999";
            UUID scanId = UUID.randomUUID();
            when(stockTakeStore.findScan(scanId)).thenReturn(Optional.empty());
            when(catalogueQueries.lookupByBarcode(any(), eq(OWN_SCOPE))).thenReturn(Optional.empty());

            SkuView foundSku = mock(SkuView.class);
            when(foundSku.skuId()).thenReturn(skuId);
            when(foundSku.skuCode()).thenReturn(code);
            when(foundSku.nameEn()).thenReturn("Biscuits");

            when(catalogueQueries.searchSku(any(SkuFilter.class), eq(OWN_SCOPE)))
                    .thenReturn(new SkuPage(List.of(foundSku), 1));
            when(stockTakeStore.lotsForSkuAtLocation(LOCATION, skuId, "GOOD"))
                    .thenReturn(
                            List.of(new StockTakeStore.LotCandidate(batchId, "GOOD", new BigDecimal("10.000"), null)));

            BatchView batch = mock(BatchView.class);
            when(batch.batchId()).thenReturn(batchId);
            when(batch.batchNo()).thenReturn("BATCH-7");
            when(batchQueries.getBatch(batchId, OWN_SCOPE)).thenReturn(Optional.of(batch));
            when(stockTakeStore.totalCountedForLot(taskId, batchId, "GOOD")).thenReturn(new BigDecimal("1.000"));

            RecordStockTakeScan scan = new RecordStockTakeScan(
                    taskId, scanId, code, null, null, null, LotCondition.GOOD, BigDecimal.ONE, null, null, null);

            StockTakeScanResult result = handler.handle(scan, OWN_SCOPE);

            assertThat(result.skuCode()).isEqualTo("SKU-999");
            assertThat(result.batchNo()).isEqualTo("BATCH-7");
            verify(jdbc)
                    .update(
                            contains("insert into inventory.stock_take_scan"),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void itemOutOfScopeForSkusCountThrows() {
            UUID otherSku = UUID.randomUUID();
            CountStore.Task scopedTask = new CountStore.Task(
                    taskId, ENTITY, LOCATION, "SKUS", List.of(otherSku), "COUNTING", null, null, null, clock.instant());
            when(countStore.lock(taskId)).thenReturn(Optional.of(scopedTask));

            LookupResult lookup = new LookupResult(
                    skuId, "SKU-001", "Tea 100g", null, null, false, false, "g", null, null, null, false, false, false);
            when(catalogueQueries.lookupByBarcode(any(), eq(OWN_SCOPE))).thenReturn(Optional.of(lookup));

            RecordStockTakeScan scan = new RecordStockTakeScan(
                    taskId,
                    UUID.randomUUID(),
                    "479001",
                    null,
                    null,
                    null,
                    LotCondition.GOOD,
                    BigDecimal.ONE,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> handler.handle(scan, OWN_SCOPE))
                    .isInstanceOf(ProblemException.class)
                    .hasMessageContaining("m5.count.line_out_of_scope");
        }
    }

    @Nested
    @DisplayName("BatchStockTakeScan")
    class BatchScanTests {

        private BatchStockTakeScanHandler batchHandler;
        private UUID taskId;

        @BeforeEach
        void init() {
            batchHandler = new BatchStockTakeScanHandler(
                    countStore, stockTakeStore, catalogueQueries, batchQueries, policy, jdbc, clock, audit, events);
            taskId = UUID.randomUUID();
            CountStore.Task counting = new CountStore.Task(
                    taskId, ENTITY, LOCATION, "FULL", List.of(), "COUNTING", null, null, null, clock.instant());
            when(countStore.lock(taskId)).thenReturn(Optional.of(counting));
        }

        @Test
        void emptyBatchThrows() {
            assertThatThrownBy(() -> batchHandler.handle(new BatchStockTakeScan(taskId, List.of()), OWN_SCOPE))
                    .isInstanceOf(ProblemException.class)
                    .hasMessageContaining("m5.stock_take.scans_empty");
        }

        @Test
        void processesBatchWithDuplicatesTrackedSeparately() {
            UUID s1Id = UUID.randomUUID();
            UUID s2Id = UUID.randomUUID();
            UUID sku1 = UUID.randomUUID();
            UUID batch1 = UUID.randomUUID();
            RecordStockTakeScan s1 = new RecordStockTakeScan(
                    taskId, s1Id, "111", sku1, null, batch1, LotCondition.GOOD, BigDecimal.ONE, null, null, null);
            RecordStockTakeScan s2 = new RecordStockTakeScan(
                    taskId, s2Id, "222", sku1, null, batch1, LotCondition.GOOD, BigDecimal.ONE, null, null, null);

            when(stockTakeStore.findScan(s1Id)).thenReturn(Optional.empty());
            when(stockTakeStore.findScan(s2Id))
                    .thenReturn(Optional.of(new StockTakeStore.ScanRow(
                            s2Id,
                            taskId,
                            ENTITY,
                            LOCATION,
                            "222",
                            sku1,
                            batch1,
                            "GOOD",
                            BigDecimal.ONE,
                            clock.instant(),
                            USER,
                            null,
                            null)));

            when(batchQueries.getBatch(eq(batch1), any()))
                    .thenReturn(Optional.of(new BatchView(
                            batch1,
                            sku1,
                            null,
                            "B1",
                            null,
                            null,
                            null,
                            null,
                            false,
                            null,
                            "ACTIVE",
                            ENTITY,
                            clock.instant())));

            StockTakeBatchScanResult result =
                    batchHandler.handle(new BatchStockTakeScan(taskId, List.of(s1, s2)), OWN_SCOPE);

            assertThat(result.processedCount()).isEqualTo(2);
            assertThat(result.duplicateCount()).isEqualTo(1);
            verify(audit).record(eq("STOCK_TAKE_BATCH_SCANNED"), any(), any(), any(), eq(OWN_SCOPE));
        }
    }

    @Nested
    @DisplayName("ReconcileStockTakeSession")
    class ReconcileSessionTests {

        private lk.coopfed.knoweb.m5inventory.internal.control.StockOnHand stock;
        private lk.coopfed.knoweb.m5inventory.api.StockLedger ledger;
        private ReconcileStockTakeSessionHandler handler;
        private UUID taskId;
        private UUID batch1;
        private UUID batch2;
        private UUID sku1;
        private UUID sku2;

        @BeforeEach
        void init() {
            stock = mock(lk.coopfed.knoweb.m5inventory.internal.control.StockOnHand.class);
            ledger = mock(lk.coopfed.knoweb.m5inventory.api.StockLedger.class);
            handler = new ReconcileStockTakeSessionHandler(
                    countStore, stockTakeStore, policy, batchQueries, stock, ledger, jdbc, clock, audit, events);
            taskId = UUID.randomUUID();
            batch1 = UUID.randomUUID();
            batch2 = UUID.randomUUID();
            sku1 = UUID.randomUUID();
            sku2 = UUID.randomUUID();

            CountStore.Task task = new CountStore.Task(
                    taskId, ENTITY, LOCATION, "FULL", List.of(), "COUNTING", null, null, null, clock.instant());
            when(countStore.lock(taskId)).thenReturn(Optional.of(task));

            when(batchQueries.getBatch(eq(batch1), any()))
                    .thenReturn(Optional.of(new BatchView(
                            batch1,
                            sku1,
                            null,
                            "B1",
                            null,
                            null,
                            null,
                            null,
                            false,
                            null,
                            "ACTIVE",
                            ENTITY,
                            clock.instant())));
            when(batchQueries.getBatch(eq(batch2), any()))
                    .thenReturn(Optional.of(new BatchView(
                            batch2,
                            sku2,
                            null,
                            "B2",
                            null,
                            null,
                            null,
                            null,
                            false,
                            null,
                            "ACTIVE",
                            ENTITY,
                            clock.instant())));

            when(stock.unitValue(any(), any(), any(), any(), any())).thenReturn(new BigDecimal("10.0000"));
            when(policy.withinTolerance(any(), any(), any())).thenReturn(true);
            when(policy.withinValueTolerance(any(), any())).thenReturn(true);
            when(policy.autopostValueCap(any())).thenReturn(new BigDecimal("1000.00"));
        }

        @Test
        void invalidUncountedHandlingThrows() {
            assertThatThrownBy(() -> handler.handle(new ReconcileStockTakeSession(taskId, "INVALID", null), OWN_SCOPE))
                    .isInstanceOf(ProblemException.class)
                    .hasMessageContaining("m5.stock_take.uncounted_handling_invalid");
        }

        @Test
        void skipWithoutReasonThrows() {
            assertThatThrownBy(() -> handler.handle(new ReconcileStockTakeSession(taskId, "SKIP", ""), OWN_SCOPE))
                    .isInstanceOf(ProblemException.class)
                    .hasMessageContaining("m5.stock_take.skip_reason_required");
        }

        @Test
        void uncountedZeroSetsZeroQuantityForUnscannedLots() {
            when(countStore.expectation(taskId))
                    .thenReturn(List.of(
                            new CountStore.Lot(batch1, sku1, "GOOD", new BigDecimal("10.000")),
                            new CountStore.Lot(batch2, sku2, "GOOD", new BigDecimal("5.000"))));

            when(stockTakeStore.aggregatedCounts(taskId))
                    .thenReturn(List.of(new StockTakeStore.AggregatedLotCount(
                            sku1, batch1, "GOOD", new BigDecimal("8.000"), clock.instant())));

            UUID result = handler.handle(new ReconcileStockTakeSession(taskId, "ZERO", null), OWN_SCOPE);

            assertThat(result).isEqualTo(taskId);
            verify(audit).record(eq("STOCK_TAKE_SESSION_RECONCILED"), any(), any(), any(), eq(OWN_SCOPE));
        }

        @Test
        void uncountedSkipSetsSkipReasonForUnscannedLots() {
            when(countStore.expectation(taskId))
                    .thenReturn(List.of(
                            new CountStore.Lot(batch1, sku1, "GOOD", new BigDecimal("10.000")),
                            new CountStore.Lot(batch2, sku2, "GOOD", new BigDecimal("5.000"))));

            when(stockTakeStore.aggregatedCounts(taskId))
                    .thenReturn(List.of(new StockTakeStore.AggregatedLotCount(
                            sku1, batch1, "GOOD", new BigDecimal("8.000"), clock.instant())));

            UUID result = handler.handle(new ReconcileStockTakeSession(taskId, "SKIP", "Locked room"), OWN_SCOPE);

            assertThat(result).isEqualTo(taskId);
            verify(audit).record(eq("STOCK_TAKE_SESSION_RECONCILED"), any(), any(), any(), eq(OWN_SCOPE));
        }
    }

    @Test
    void auditEventTypesAreRegisteredWithValidSeverities() {
        org.springframework.beans.factory.config.YamlMapFactoryBean yaml =
                new org.springframework.beans.factory.config.YamlMapFactoryBean();
        yaml.setResources(new org.springframework.core.io.ClassPathResource("seed/m5inventory/audit-event-types.yaml"));
        Map<String, Object> root = yaml.getObject();
        assertThat(root).isNotNull();
        List<Map<String, Object>> events = (List<Map<String, Object>>) root.get("audit_event_types");
        assertThat(events)
                .filteredOn(e -> ((String) e.get("code")).startsWith("STOCK_TAKE_"))
                .extracting(e -> e.get("code"))
                .containsExactlyInAnyOrder(
                        "STOCK_TAKE_SESSION_OPENED",
                        "STOCK_TAKE_SCAN_RECORDED",
                        "STOCK_TAKE_BATCH_SCANNED",
                        "STOCK_TAKE_SESSION_RECONCILED");

        assertThat(events)
                .filteredOn(e -> ((String) e.get("code")).startsWith("STOCK_TAKE_"))
                .allSatisfy(e -> assertThat(List.of("INFO", "REVIEW", "ALERT")).contains((String) e.get("severity")));
    }
}
