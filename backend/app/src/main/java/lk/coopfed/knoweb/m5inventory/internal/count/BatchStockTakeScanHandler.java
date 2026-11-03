package lk.coopfed.knoweb.m5inventory.internal.count;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.query.BarcodeLookup;
import lk.coopfed.knoweb.m2catalogue.query.BatchFilter;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchView;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.LookupResult;
import lk.coopfed.knoweb.m2catalogue.query.SkuFilter;
import lk.coopfed.knoweb.m2catalogue.query.SkuPage;
import lk.coopfed.knoweb.m5inventory.api.BatchStockTakeScan;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.RecordStockTakeScan;
import lk.coopfed.knoweb.m5inventory.api.StockTakeBatchScanned;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import lk.coopfed.knoweb.m5inventory.query.StockTakeBatchScanResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BatchStockTakeScanHandler (PGL-M2-09): processes an offline batch of mobile scans with row-level
 * deduplication, unblocking offline sync and retry queues without double-counting.
 */
@Service
@CommandHandler(permission = "shop.count.record")
class BatchStockTakeScanHandler implements Handles<BatchStockTakeScan, StockTakeBatchScanResult> {

    static final String AUDIT_BATCH_SCANNED = "STOCK_TAKE_BATCH_SCANNED";

    private final CountStore countStore;
    private final StockTakeStore stockTakeStore;
    private final CatalogueQueries catalogueQueries;
    private final BatchQueries batchQueries;
    private final ControlPolicy policy;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    BatchStockTakeScanHandler(
            CountStore countStore,
            StockTakeStore stockTakeStore,
            CatalogueQueries catalogueQueries,
            BatchQueries batchQueries,
            ControlPolicy policy,
            JdbcTemplate jdbc,
            Clock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.countStore = countStore;
        this.stockTakeStore = stockTakeStore;
        this.catalogueQueries = catalogueQueries;
        this.batchQueries = batchQueries;
        this.policy = policy;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public StockTakeBatchScanResult handle(BatchStockTakeScan command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        if (command.sessionId() == null) {
            throw new ProblemException("m5.count.not_found");
        }
        CountStore.Task task = countStore
                .lock(command.sessionId())
                .orElseThrow(() -> new ProblemException(
                        "m5.count.not_found",
                        Map.of("taskId", command.sessionId().toString())));
        if (!"COUNTING".equals(task.status())) {
            throw new ProblemException("m5.count.not_counting", Map.of("status", task.status()));
        }
        policy.requireEntityLocation(task.locationId(), scope);

        if (command.scans() == null || command.scans().isEmpty()) {
            throw new ProblemException("m5.stock_take.scans_empty");
        }
        if (command.scans().size() > 1000) {
            throw new ProblemException("m5.stock_take.batch_too_large");
        }

        int processed = 0;
        int duplicate = 0;

        for (RecordStockTakeScan scan : command.scans()) {
            if (scan.quantity() == null
                    || scan.quantity().signum() <= 0
                    || scan.quantity().stripTrailingZeros().scale() > 3) {
                throw new ProblemException("m5.scan.quantity_invalid");
            }

            LotCondition condition = scan.condition() == null ? LotCondition.GOOD : scan.condition();

            // 1. Strict Idempotency & Conflict Check
            if (scan.scanId() != null) {
                var existingOpt = stockTakeStore.findScan(scan.scanId());
                if (existingOpt.isPresent()) {
                    StockTakeStore.ScanRow existing = existingOpt.get();
                    boolean sameTask = existing.taskId().equals(task.taskId());
                    boolean sameLocation = existing.locationId().equals(task.locationId());
                    boolean sameCondition = existing.condition().equals(condition.name());
                    boolean sameQty = existing.countedQty().compareTo(scan.quantity()) == 0;
                    boolean sameSku = scan.skuId() == null || existing.skuId().equals(scan.skuId());
                    boolean sameBatch =
                            scan.batchId() == null || existing.batchId().equals(scan.batchId());
                    boolean sameCode = scan.barcode() == null
                            || scan.barcode().isBlank()
                            || existing.scannedCode().isBlank()
                            || existing.scannedCode().equals(scan.barcode().trim());

                    if (sameTask && sameLocation && sameCondition && sameQty && sameSku && sameBatch && sameCode) {
                        duplicate++;
                        continue;
                    }
                    throw new ProblemException(
                            "m5.stock_take.scan_conflict",
                            Map.of(
                                    "scanId",
                                    scan.scanId().toString(),
                                    "reason",
                                    "Conflicting retry with different attributes"));
                }
            }

            // 2. Barcode & SKU Resolution
            UUID skuId = null;
            String skuCode = null;
            LookupResult.BatchRef batchFromBarcode = null;
            String scannedCode = "";

            if (scan.barcode() != null && !scan.barcode().isBlank()) {
                scannedCode = scan.barcode().trim();
                var lookup = catalogueQueries.lookupByBarcode(
                        new BarcodeLookup(scannedCode, null, null, null, null, task.locationId()), scope);
                if (lookup.isPresent()) {
                    skuId = lookup.get().skuId();
                    skuCode = lookup.get().skuCode();
                    batchFromBarcode = lookup.get().batch();
                } else {
                    SkuPage page = catalogueQueries.searchSku(new SkuFilter(null, scannedCode, null, 0, 1), scope);
                    if (page != null && page.items() != null && !page.items().isEmpty()) {
                        skuId = page.items().get(0).skuId();
                        skuCode = page.items().get(0).skuCode();
                    }
                }
            }

            if (skuId == null && scan.skuId() != null) {
                skuId = scan.skuId();
                var skuOpt = catalogueQueries.getSku(scan.skuId(), scope);
                if (skuOpt.isPresent()) {
                    skuCode = skuOpt.get().skuCode();
                    if (scannedCode.isBlank()) {
                        scannedCode = skuCode;
                    }
                }
            }

            if (skuId == null && scan.skuCode() != null && !scan.skuCode().isBlank()) {
                String code = scan.skuCode().trim();
                SkuPage page = catalogueQueries.searchSku(new SkuFilter(null, code, null, 0, 1), scope);
                if (page != null && page.items() != null && !page.items().isEmpty()) {
                    skuId = page.items().get(0).skuId();
                    skuCode = page.items().get(0).skuCode();
                    if (scannedCode.isBlank()) {
                        scannedCode = skuCode;
                    }
                }
            }

            if (skuId == null) {
                throw new ProblemException("m5.stock_take.sku_not_found", Map.of("scannedCode", scannedCode));
            }

            // 3. Scope Enforcement
            if ("SKUS".equals(task.scopeKind()) && !task.skuIds().contains(skuId)) {
                throw new ProblemException("m5.count.line_out_of_scope", Map.of("skuId", skuId.toString()));
            }

            // 4. Batch Resolution
            UUID batchId = null;
            if (scan.batchId() != null) {
                BatchView batch = batchQueries
                        .getBatch(scan.batchId(), scope)
                        .orElseThrow(() -> new ProblemException(
                                "m5.batch.not_found",
                                Map.of("batchId", scan.batchId().toString())));
                batchId = batch.batchId();
            } else if (batchFromBarcode != null && batchFromBarcode.batchId() != null) {
                batchId = batchFromBarcode.batchId();
            } else {
                var lots = stockTakeStore.lotsForSkuAtLocation(task.locationId(), skuId, condition.name());
                if (!lots.isEmpty()) {
                    batchId = lots.get(0).batchId();
                } else {
                    List<BatchView> batches = batchQueries.listBatches(new BatchFilter(skuId, null, null, null), scope);
                    if (!batches.isEmpty()) {
                        batchId = batches.get(0).batchId();
                    } else {
                        throw new ProblemException("m5.batch.not_found", Map.of("skuId", skuId.toString()));
                    }
                }
            }

            UUID newScanId = scan.scanId() != null ? scan.scanId() : Ids.next();
            Instant scannedTime = scan.scannedAt() != null ? scan.scannedAt() : clock.instant();

            jdbc.update(
                    """
                    insert into inventory.stock_take_scan
                        (scan_id, task_id, owner_entity_id, location_id, scanned_code, sku_id, batch_id,
                         condition, counted_qty, scanned_at, scanned_by, device_id, notes)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    newScanId,
                    task.taskId(),
                    task.ownerEntityId(),
                    task.locationId(),
                    scannedCode,
                    skuId,
                    batchId,
                    condition.name(),
                    scan.quantity(),
                    Timestamp.from(scannedTime),
                    scope.userId(),
                    scan.deviceId() != null ? scan.deviceId().trim() : null,
                    scan.notes() != null ? scan.notes().trim() : null);

            processed++;
        }

        audit.record(
                AUDIT_BATCH_SCANNED,
                Subject.of("stock_take_session", task.taskId()),
                null,
                Map.of(
                        "processedCount", processed,
                        "duplicateCount", duplicate,
                        "totalScansCount", command.scans().size()),
                scope);

        events.publish(new StockTakeBatchScanned(
                task.taskId(), task.ownerEntityId(), task.locationId(), processed, duplicate));

        return new StockTakeBatchScanResult(
                task.taskId(), processed, duplicate, command.scans().size());
    }
}
