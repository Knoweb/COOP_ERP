package lk.coopfed.knoweb.m5inventory.internal.count;

import java.math.BigDecimal;
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
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.RecordStockTakeScan;
import lk.coopfed.knoweb.m5inventory.api.StockTakeScanRecorded;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import lk.coopfed.knoweb.m5inventory.query.StockTakeScanResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RecordStockTakeScanHandler (PGL-M2-09): accepts barcode and SKU scans and records counted quantities
 * with strict idempotency deduplication by scanId.
 */
@Service
@CommandHandler(permission = "shop.count.record")
class RecordStockTakeScanHandler implements Handles<RecordStockTakeScan, StockTakeScanResult> {

    static final String AUDIT_RECORDED = "STOCK_TAKE_SCAN_RECORDED";

    private final CountStore countStore;
    private final StockTakeStore stockTakeStore;
    private final JdbcTemplate jdbc;
    private final ControlPolicy policy;
    private final CatalogueQueries catalogueQueries;
    private final BatchQueries batchQueries;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    RecordStockTakeScanHandler(
            CountStore countStore,
            StockTakeStore stockTakeStore,
            JdbcTemplate jdbc,
            ControlPolicy policy,
            CatalogueQueries catalogueQueries,
            BatchQueries batchQueries,
            Clock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.countStore = countStore;
        this.stockTakeStore = stockTakeStore;
        this.jdbc = jdbc;
        this.policy = policy;
        this.catalogueQueries = catalogueQueries;
        this.batchQueries = batchQueries;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public StockTakeScanResult handle(RecordStockTakeScan command, ScopeContext scope) {
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

        if (command.quantity() == null
                || command.quantity().signum() <= 0
                || command.quantity().stripTrailingZeros().scale() > 3) {
            throw new ProblemException("m5.scan.quantity_invalid");
        }

        LotCondition condition = command.condition() == null ? LotCondition.GOOD : command.condition();

        // 1. Strict Idempotency & Conflict Check
        if (command.scanId() != null) {
            var existingOpt = stockTakeStore.findScan(command.scanId());
            if (existingOpt.isPresent()) {
                StockTakeStore.ScanRow existing = existingOpt.get();
                boolean sameTask = existing.taskId().equals(task.taskId());
                boolean sameLocation = existing.locationId().equals(task.locationId());
                boolean sameCondition = existing.condition().equals(condition.name());
                boolean sameQty = existing.countedQty().compareTo(command.quantity()) == 0;
                boolean sameSku = command.skuId() == null || existing.skuId().equals(command.skuId());
                boolean sameBatch =
                        command.batchId() == null || existing.batchId().equals(command.batchId());
                boolean sameCode = command.barcode() == null
                        || command.barcode().isBlank()
                        || existing.scannedCode().isBlank()
                        || existing.scannedCode().equals(command.barcode().trim());

                if (sameTask && sameLocation && sameCondition && sameQty && sameSku && sameBatch && sameCode) {
                    BigDecimal total =
                            stockTakeStore.totalCountedForLot(task.taskId(), existing.batchId(), existing.condition());
                    SkuView sku =
                            catalogueQueries.getSku(existing.skuId(), scope).orElse(null);
                    BatchView batch =
                            batchQueries.getBatch(existing.batchId(), scope).orElse(null);
                    return new StockTakeScanResult(
                            existing.scanId(),
                            existing.skuId(),
                            sku != null ? sku.skuCode() : "",
                            sku != null ? sku.nameEn() : "",
                            existing.batchId(),
                            batch != null ? batch.batchNo() : "",
                            existing.condition(),
                            existing.countedQty(),
                            total,
                            true);
                }
                // Conflicting retry with the same scan ID
                throw new ProblemException(
                        "m5.stock_take.scan_conflict",
                        Map.of("scanId", command.scanId().toString()));
            }
        }

        // 2. SKU Resolution (Barcode -> SkuId -> SkuCode)
        UUID skuId = null;
        String skuCode = null;
        String skuNameEn = null;
        LookupResult.BatchRef batchFromBarcode = null;
        String scannedCode = "";

        if (command.barcode() != null && !command.barcode().isBlank()) {
            scannedCode = command.barcode().trim();
            var lookup = catalogueQueries.lookupByBarcode(
                    new BarcodeLookup(scannedCode, null, null, null, null, task.locationId()), scope);
            if (lookup.isPresent()) {
                skuId = lookup.get().skuId();
                skuCode = lookup.get().skuCode();
                skuNameEn = lookup.get().nameEn();
                batchFromBarcode = lookup.get().batch();
            } else {
                // Try treating barcode string as SKU code
                SkuPage page = catalogueQueries.searchSku(new SkuFilter(null, scannedCode, null, 0, 1), scope);
                if (page.items() != null && !page.items().isEmpty()) {
                    skuId = page.items().get(0).skuId();
                    skuCode = page.items().get(0).skuCode();
                    skuNameEn = page.items().get(0).nameEn();
                }
            }
        }

        if (skuId == null && command.skuId() != null) {
            var skuOpt = catalogueQueries.getSku(command.skuId(), scope);
            if (skuOpt.isPresent()) {
                skuId = skuOpt.get().skuId();
                skuCode = skuOpt.get().skuCode();
                skuNameEn = skuOpt.get().nameEn();
                if (scannedCode.isBlank()) {
                    scannedCode = skuCode;
                }
            }
        }

        if (skuId == null && command.skuCode() != null && !command.skuCode().isBlank()) {
            String code = command.skuCode().trim();
            SkuPage page = catalogueQueries.searchSku(new SkuFilter(null, code, null, 0, 1), scope);
            if (page.items() != null && !page.items().isEmpty()) {
                skuId = page.items().get(0).skuId();
                skuCode = page.items().get(0).skuCode();
                skuNameEn = page.items().get(0).nameEn();
                if (scannedCode.isBlank()) {
                    scannedCode = skuCode;
                }
            }
        }

        if (skuId == null) {
            throw new ProblemException("m5.stock_take.sku_not_found", Map.of("scannedCode", scannedCode));
        }

        // Scope check for SKUS-type count tasks
        if ("SKUS".equals(task.scopeKind()) && !task.skuIds().contains(skuId)) {
            throw new ProblemException("m5.count.line_out_of_scope", Map.of("skuId", skuId.toString()));
        }

        // 3. Batch Resolution
        UUID batchId = null;
        String batchNo = "";

        if (command.batchId() != null) {
            BatchView batch = batchQueries
                    .getBatch(command.batchId(), scope)
                    .orElseThrow(() -> new ProblemException(
                            "m5.batch.not_found",
                            Map.of("batchId", command.batchId().toString())));
            batchId = batch.batchId();
            batchNo = batch.batchNo();
        } else if (batchFromBarcode != null && batchFromBarcode.batchId() != null) {
            batchId = batchFromBarcode.batchId();
            batchNo = batchFromBarcode.batchNo();
        } else {
            // Find existing lots at this location for the SKU and condition
            var lots = stockTakeStore.lotsForSkuAtLocation(task.locationId(), skuId, condition.name());
            if (!lots.isEmpty()) {
                batchId = lots.get(0).batchId();
                BatchView b = batchQueries.getBatch(batchId, scope).orElse(null);
                batchNo = b != null ? b.batchNo() : "";
            } else {
                // If no lots exist at location, find catalogue batches for this SKU
                List<BatchView> batches = batchQueries.listBatches(new BatchFilter(skuId, null, null, null), scope);
                if (!batches.isEmpty()) {
                    batchId = batches.get(0).batchId();
                    batchNo = batches.get(0).batchNo();
                } else {
                    throw new ProblemException("m5.batch.not_found", Map.of("skuId", skuId.toString()));
                }
            }
        }

        // 4. Persistence & Audit
        UUID scanId = command.scanId() != null ? command.scanId() : Ids.next();
        Instant scannedAt = command.scannedAt() != null ? command.scannedAt() : clock.instant();

        jdbc.update(
                """
                insert into inventory.stock_take_scan
                    (scan_id, task_id, owner_entity_id, location_id, scanned_code, sku_id, batch_id,
                     condition, counted_qty, scanned_at, scanned_by, device_id, notes)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                scanId,
                task.taskId(),
                task.ownerEntityId(),
                task.locationId(),
                scannedCode,
                skuId,
                batchId,
                condition.name(),
                command.quantity(),
                Timestamp.from(scannedAt),
                scope.userId(),
                command.deviceId(),
                command.notes());

        BigDecimal totalCounted = stockTakeStore.totalCountedForLot(task.taskId(), batchId, condition.name());

        audit.record(
                AUDIT_RECORDED,
                Subject.of("stock_take_scan", scanId),
                null,
                Map.of(
                        "taskId", task.taskId(),
                        "skuId", skuId,
                        "batchId", batchId,
                        "condition", condition.name(),
                        "quantity", command.quantity().toPlainString(),
                        "totalCounted", totalCounted.toPlainString()),
                scope);

        events.publish(new StockTakeScanRecorded(
                task.taskId(),
                scanId,
                task.ownerEntityId(),
                task.locationId(),
                skuId,
                batchId,
                condition,
                command.quantity(),
                totalCounted));

        return new StockTakeScanResult(
                scanId,
                skuId,
                skuCode,
                skuNameEn,
                batchId,
                batchNo,
                condition.name(),
                command.quantity(),
                totalCounted,
                false);
    }
}
