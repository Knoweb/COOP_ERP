package lk.coopfed.knoweb.m5inventory.internal.count;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.query.BarcodeView;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchView;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import lk.coopfed.knoweb.m5inventory.query.StockTakeQueries;
import lk.coopfed.knoweb.m5inventory.query.StockTakeReviewLine;
import lk.coopfed.knoweb.m5inventory.query.StockTakeReviewView;
import lk.coopfed.knoweb.m5inventory.query.StockTakeScanView;
import lk.coopfed.knoweb.m5inventory.query.StockTakeSessionView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class StockTakeQueriesImpl implements StockTakeQueries {

    private final CountStore countStore;
    private final StockTakeStore stockTakeStore;
    private final ControlPolicy policy;
    private final CatalogueQueries catalogueQueries;
    private final BatchQueries batchQueries;
    private final JdbcTemplate jdbc;

    StockTakeQueriesImpl(
            CountStore countStore,
            StockTakeStore stockTakeStore,
            ControlPolicy policy,
            CatalogueQueries catalogueQueries,
            BatchQueries batchQueries,
            JdbcTemplate jdbc) {
        this.countStore = countStore;
        this.stockTakeStore = stockTakeStore;
        this.policy = policy;
        this.catalogueQueries = catalogueQueries;
        this.batchQueries = batchQueries;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StockTakeSessionView> activeSession(UUID locationId, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        policy.requireEntityLocation(locationId, scope);

        return stockTakeStore.activeTask(locationId).map(this::toSessionView);
    }

    @Override
    public Optional<StockTakeSessionView> getSession(UUID sessionId, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        return countStore.lock(sessionId).map(this::toSessionView);
    }

    private StockTakeSessionView toSessionView(CountStore.Task task) {
        int expectedLots = stockTakeStore.expectedLotsCount(task.taskId());
        int totalScans = stockTakeStore.totalScansCount(task.taskId());
        BigDecimal totalUnits = stockTakeStore.totalCountedUnits(task.taskId());

        return new StockTakeSessionView(
                task.taskId(),
                task.ownerEntityId(),
                task.locationId(),
                task.scopeKind(),
                task.skuIds(),
                task.status(),
                task.startedAt(),
                task.submittedBy(),
                expectedLots,
                totalScans,
                totalUnits);
    }

    @Override
    public StockTakeReviewView getReview(UUID sessionId, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        CountStore.Task task = countStore
                .lock(sessionId)
                .orElseThrow(() -> new ProblemException("m5.count.not_found", Map.of("taskId", sessionId.toString())));

        List<CountStore.Lot> expectations = countStore.expectation(task.taskId());
        List<StockTakeStore.AggregatedLotCount> scans = stockTakeStore.aggregatedCounts(task.taskId());

        Map<String, StockTakeStore.AggregatedLotCount> scannedByLot = new HashMap<>();
        for (StockTakeStore.AggregatedLotCount scan : scans) {
            scannedByLot.put(key(scan.batchId(), scan.condition()), scan);
        }

        List<StockTakeReviewLine> lines = new ArrayList<>();
        Set<String> processedKeys = new HashSet<>();

        int matchedLots = 0;
        int discrepantLots = 0;
        int uncountedLots = 0;
        BigDecimal totalVarianceQty = BigDecimal.ZERO;
        BigDecimal totalVarianceValue = BigDecimal.ZERO;

        Map<UUID, SkuView> skuCache = new HashMap<>();
        Map<UUID, BatchView> batchCache = new HashMap<>();

        // 1. Process expected lots
        for (CountStore.Lot exp : expectations) {
            String lotKey = key(exp.batchId(), exp.condition());
            processedKeys.add(lotKey);
            StockTakeStore.AggregatedLotCount scan = scannedByLot.get(lotKey);

            BigDecimal expectedQty = exp.qtyOnHand();
            BigDecimal countedQty = scan != null ? scan.totalQty() : BigDecimal.ZERO;
            BigDecimal varianceQty = countedQty.subtract(expectedQty);
            BigDecimal unitCost = resolveLotUnitCost(task.locationId(), exp.batchId(), exp.condition(), exp.skuId());
            BigDecimal varianceVal = varianceQty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);

            String status;
            if (scan == null || countedQty.signum() == 0) {
                status = "UNCOUNTED";
                uncountedLots++;
                if (expectedQty.signum() != 0) {
                    discrepantLots++;
                }
            } else if (varianceQty.signum() == 0) {
                status = "MATCHED";
                matchedLots++;
            } else {
                status = varianceQty.signum() > 0 ? "OVER" : "SHORT";
                discrepantLots++;
            }

            totalVarianceQty = totalVarianceQty.add(varianceQty);
            totalVarianceValue = totalVarianceValue.add(varianceVal);

            SkuView sku = skuCache.computeIfAbsent(
                    exp.skuId(), id -> catalogueQueries.getSku(id, scope).orElse(null));
            BatchView batch = batchCache.computeIfAbsent(
                    exp.batchId(), id -> batchQueries.getBatch(id, scope).orElse(null));
            String barcode = resolveFirstBarcode(exp.skuId(), scope);

            lines.add(new StockTakeReviewLine(
                    exp.skuId(),
                    sku != null ? sku.skuCode() : "",
                    sku != null ? sku.nameEn() : "",
                    sku != null ? sku.nameSi() : "",
                    sku != null ? sku.nameTa() : "",
                    barcode,
                    exp.batchId(),
                    batch != null ? batch.batchNo() : "",
                    batch != null ? batch.expiryDate() : null,
                    exp.condition(),
                    expectedQty,
                    countedQty,
                    varianceQty,
                    unitCost,
                    varianceVal,
                    status));
        }

        // 2. Process unexpected lots (shelf surprises found during counting)
        for (StockTakeStore.AggregatedLotCount scan : scans) {
            String lotKey = key(scan.batchId(), scan.condition());
            if (!processedKeys.contains(lotKey)) {
                BigDecimal expectedQty = BigDecimal.ZERO;
                BigDecimal countedQty = scan.totalQty();
                BigDecimal varianceQty = countedQty;
                BigDecimal unitCost =
                        resolveLotUnitCost(task.locationId(), scan.batchId(), scan.condition(), scan.skuId());
                BigDecimal varianceVal = varianceQty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);

                discrepantLots++;
                totalVarianceQty = totalVarianceQty.add(varianceQty);
                totalVarianceValue = totalVarianceValue.add(varianceVal);

                SkuView sku = skuCache.computeIfAbsent(
                        scan.skuId(), id -> catalogueQueries.getSku(id, scope).orElse(null));
                BatchView batch = batchCache.computeIfAbsent(
                        scan.batchId(), id -> batchQueries.getBatch(id, scope).orElse(null));
                String barcode = resolveFirstBarcode(scan.skuId(), scope);

                lines.add(new StockTakeReviewLine(
                        scan.skuId(),
                        sku != null ? sku.skuCode() : "",
                        sku != null ? sku.nameEn() : "",
                        sku != null ? sku.nameSi() : "",
                        sku != null ? sku.nameTa() : "",
                        barcode,
                        scan.batchId(),
                        batch != null ? batch.batchNo() : "",
                        batch != null ? batch.expiryDate() : null,
                        scan.condition(),
                        expectedQty,
                        countedQty,
                        varianceQty,
                        unitCost,
                        varianceVal,
                        "UNEXPECTED"));
            }
        }

        return new StockTakeReviewView(
                task.taskId(),
                task.locationId(),
                task.status(),
                expectations.size(),
                scans.size(),
                matchedLots,
                discrepantLots,
                uncountedLots,
                totalVarianceQty,
                totalVarianceValue,
                lines);
    }

    @Override
    public List<StockTakeScanView> listScans(UUID sessionId, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        CountStore.Task task = countStore
                .lock(sessionId)
                .orElseThrow(() -> new ProblemException("m5.count.not_found", Map.of("taskId", sessionId.toString())));

        List<StockTakeStore.ScanRow> rows = stockTakeStore.scansForTask(task.taskId());
        Map<UUID, SkuView> skuCache = new HashMap<>();
        Map<UUID, BatchView> batchCache = new HashMap<>();

        return rows.stream()
                .map(row -> {
                    SkuView sku = skuCache.computeIfAbsent(
                            row.skuId(),
                            id -> catalogueQueries.getSku(id, scope).orElse(null));
                    BatchView batch = batchCache.computeIfAbsent(
                            row.batchId(),
                            id -> batchQueries.getBatch(id, scope).orElse(null));
                    return new StockTakeScanView(
                            row.scanId(),
                            row.taskId(),
                            row.scannedCode(),
                            row.skuId(),
                            sku != null ? sku.skuCode() : "",
                            sku != null ? sku.nameEn() : "",
                            row.batchId(),
                            batch != null ? batch.batchNo() : "",
                            row.condition(),
                            row.countedQty(),
                            row.scannedAt(),
                            row.scannedBy(),
                            row.deviceId(),
                            row.notes());
                })
                .toList();
    }

    private BigDecimal resolveLotUnitCost(UUID locationId, UUID batchId, String condition, UUID skuId) {
        List<BigDecimal> costs = jdbc.queryForList(
                "select unit_cost from inventory.stock_lot where location_id = ? and batch_id = ? and condition = ?",
                BigDecimal.class,
                locationId,
                batchId,
                condition);
        if (!costs.isEmpty() && costs.get(0) != null) {
            return costs.get(0);
        }
        // Fallback to entity average or 0
        List<BigDecimal> avg = jdbc.queryForList(
                "select avg(unit_cost) from inventory.stock_lot where sku_id = ?", BigDecimal.class, skuId);
        return (!avg.isEmpty() && avg.get(0) != null) ? avg.get(0) : BigDecimal.ZERO;
    }

    private String resolveFirstBarcode(UUID skuId, ScopeContext scope) {
        List<BarcodeView> barcodes = catalogueQueries.barcodes(skuId, scope);
        return barcodes.isEmpty() ? "" : barcodes.get(0).barcode();
    }

    private static String key(UUID batchId, String condition) {
        return batchId + "/" + condition;
    }
}
