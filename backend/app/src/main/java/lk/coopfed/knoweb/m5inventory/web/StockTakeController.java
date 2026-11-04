package lk.coopfed.knoweb.m5inventory.web;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.api.BatchStockTakeScan;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.OpenStockTakeSession;
import lk.coopfed.knoweb.m5inventory.api.ReconcileStockTakeSession;
import lk.coopfed.knoweb.m5inventory.api.RecordStockTakeScan;
import lk.coopfed.knoweb.m5inventory.query.CountView;
import lk.coopfed.knoweb.m5inventory.query.StockControlQueries;
import lk.coopfed.knoweb.m5inventory.query.StockTakeBatchScanResult;
import lk.coopfed.knoweb.m5inventory.query.StockTakeQueries;
import lk.coopfed.knoweb.m5inventory.query.StockTakeReviewView;
import lk.coopfed.knoweb.m5inventory.query.StockTakeScanResult;
import lk.coopfed.knoweb.m5inventory.query.StockTakeScanView;
import lk.coopfed.knoweb.m5inventory.query.StockTakeSessionView;
import lk.coopfed.knoweb.m5inventory.web.generated.OpenStockTakeSessionRequest;
import lk.coopfed.knoweb.m5inventory.web.generated.StockTakeApi;
import lk.coopfed.knoweb.m5inventory.web.generated.StockTakeBatchScanRequest;
import lk.coopfed.knoweb.m5inventory.web.generated.StockTakeBatchScanResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.StockTakeReconcileRequest;
import lk.coopfed.knoweb.m5inventory.web.generated.StockTakeReconcileResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.StockTakeReviewLine;
import lk.coopfed.knoweb.m5inventory.web.generated.StockTakeReviewResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.StockTakeScanItemResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.StockTakeScanRequest;
import lk.coopfed.knoweb.m5inventory.web.generated.StockTakeScanResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.StockTakeSessionResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mobile Stock-Taking REST controller (PGL-M2-09): session lifecycle, barcode scanning,
 * offline batch upload, expected vs counted variance review, and reconciliation.
 */
@RestController
public class StockTakeController implements StockTakeApi {

    private final Handles<OpenStockTakeSession, UUID> openSessionHandler;
    private final Handles<RecordStockTakeScan, StockTakeScanResult> recordScanHandler;
    private final Handles<BatchStockTakeScan, StockTakeBatchScanResult> batchScanHandler;
    private final Handles<ReconcileStockTakeSession, UUID> reconcileHandler;
    private final StockTakeQueries stockTakeQueries;
    private final StockControlQueries stockControlQueries;
    private final CurrentScope currentScope;

    public StockTakeController(
            Handles<OpenStockTakeSession, UUID> openSessionHandler,
            Handles<RecordStockTakeScan, StockTakeScanResult> recordScanHandler,
            Handles<BatchStockTakeScan, StockTakeBatchScanResult> batchScanHandler,
            Handles<ReconcileStockTakeSession, UUID> reconcileHandler,
            StockTakeQueries stockTakeQueries,
            StockControlQueries stockControlQueries,
            CurrentScope currentScope) {
        this.openSessionHandler = openSessionHandler;
        this.recordScanHandler = recordScanHandler;
        this.batchScanHandler = batchScanHandler;
        this.reconcileHandler = reconcileHandler;
        this.stockTakeQueries = stockTakeQueries;
        this.stockControlQueries = stockControlQueries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<StockTakeSessionResponse> openStockTakeSession(
            String idempotencyKey, OpenStockTakeSessionRequest request) {
        ScopeContext scope = currentScope.get();
        String kind = request.getScopeKind() != null ? request.getScopeKind().getValue() : "FULL";
        UUID sessionId = openSessionHandler.handle(
                new OpenStockTakeSession(request.getLocationId(), kind, request.getSkuIds()), scope);

        return stockTakeQueries
                .getSession(sessionId, scope)
                .map(StockTakeController::toSessionResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<StockTakeSessionResponse> getActiveStockTakeSession(UUID locationId) {
        ScopeContext scope = currentScope.get();
        return stockTakeQueries
                .activeSession(locationId, scope)
                .map(StockTakeController::toSessionResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<StockTakeSessionResponse> getStockTakeSession(UUID sessionId) {
        ScopeContext scope = currentScope.get();
        return stockTakeQueries
                .getSession(sessionId, scope)
                .map(StockTakeController::toSessionResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<StockTakeScanResponse> recordStockTakeScan(
            String idempotencyKey, UUID sessionId, StockTakeScanRequest request) {
        ScopeContext scope = currentScope.get();
        LotCondition condition = request.getCondition() != null
                ? LotCondition.valueOf(request.getCondition().getValue())
                : LotCondition.GOOD;

        StockTakeScanResult result = recordScanHandler.handle(
                new RecordStockTakeScan(
                        sessionId,
                        request.getScanId(),
                        request.getBarcode(),
                        request.getSkuId(),
                        request.getSkuCode(),
                        request.getBatchId(),
                        condition,
                        request.getQuantity(),
                        request.getScannedAt(),
                        request.getDeviceId(),
                        request.getNotes()),
                scope);

        StockTakeScanResponse response = new StockTakeScanResponse(
                result.scanId(),
                result.skuId(),
                result.skuCode(),
                result.skuNameEn(),
                result.batchId(),
                StockTakeScanResponse.ConditionEnum.fromValue(result.condition()),
                result.quantity(),
                result.totalCountedForLot(),
                result.wasDuplicate());
        response.batchNo(result.batchNo());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<List<StockTakeScanItemResponse>> listStockTakeScans(UUID sessionId) {
        ScopeContext scope = currentScope.get();
        List<StockTakeScanView> list = stockTakeQueries.listScans(sessionId, scope);
        return ResponseEntity.ok(
                list.stream().map(StockTakeController::toScanItemResponse).toList());
    }

    @Override
    public ResponseEntity<StockTakeBatchScanResponse> batchStockTakeScan(
            String idempotencyKey, UUID sessionId, StockTakeBatchScanRequest request) {
        ScopeContext scope = currentScope.get();
        List<RecordStockTakeScan> scans = request.getScans().stream()
                .map(s -> new RecordStockTakeScan(
                        sessionId,
                        s.getScanId(),
                        s.getBarcode(),
                        s.getSkuId(),
                        s.getSkuCode(),
                        s.getBatchId(),
                        s.getCondition() != null
                                ? LotCondition.valueOf(s.getCondition().getValue())
                                : LotCondition.GOOD,
                        s.getQuantity(),
                        s.getScannedAt(),
                        s.getDeviceId(),
                        s.getNotes()))
                .toList();

        StockTakeBatchScanResult result = batchScanHandler.handle(new BatchStockTakeScan(sessionId, scans), scope);
        return ResponseEntity.ok(new StockTakeBatchScanResponse(
                result.sessionId(), result.processedCount(), result.duplicateCount(), result.totalScansCount()));
    }

    @Override
    public ResponseEntity<StockTakeReviewResponse> reviewStockTakeSession(UUID sessionId) {
        ScopeContext scope = currentScope.get();
        StockTakeReviewView view = stockTakeQueries.getReview(sessionId, scope);
        return ResponseEntity.ok(toReviewResponse(view));
    }

    @Override
    public ResponseEntity<StockTakeReconcileResponse> reconcileStockTakeSession(
            String idempotencyKey, UUID sessionId, StockTakeReconcileRequest request) {
        ScopeContext scope = currentScope.get();
        String handling = request.getUncountedHandling() != null
                ? request.getUncountedHandling().getValue()
                : "ZERO";
        reconcileHandler.handle(new ReconcileStockTakeSession(sessionId, handling, request.getSkipReason()), scope);

        CountView count = stockControlQueries.count(sessionId, scope).orElseThrow();

        StockTakeReconcileResponse response = new StockTakeReconcileResponse(
                sessionId, StockTakeReconcileResponse.StatusEnum.fromValue(count.status()));
        if (count.outcome() != null) {
            response.outcome(StockTakeReconcileResponse.OutcomeEnum.fromValue(count.outcome()));
        }
        response.reviewValue(count.reviewValue());
        response.reviewBand(count.reviewBand());
        return ResponseEntity.ok(response);
    }

    private static StockTakeSessionResponse toSessionResponse(StockTakeSessionView view) {
        StockTakeSessionResponse response = new StockTakeSessionResponse(
                view.sessionId(),
                view.ownerEntityId(),
                view.locationId(),
                StockTakeSessionResponse.ScopeKindEnum.fromValue(view.scopeKind()),
                view.skuIds(),
                StockTakeSessionResponse.StatusEnum.fromValue(view.status()),
                view.expectedLotsCount(),
                view.totalScansCount(),
                view.totalCountedUnits());
        response.startedAt(view.startedAt());
        response.startedBy(view.startedBy());
        return response;
    }

    private static StockTakeScanItemResponse toScanItemResponse(StockTakeScanView v) {
        StockTakeScanItemResponse res = new StockTakeScanItemResponse(
                v.scanId(),
                v.sessionId(),
                v.scannedCode(),
                v.skuId(),
                v.skuCode(),
                v.skuNameEn(),
                v.batchId(),
                StockTakeScanItemResponse.ConditionEnum.fromValue(v.condition()),
                v.countedQty(),
                v.scannedAt());
        res.batchNo(v.batchNo());
        res.scannedBy(v.scannedBy());
        res.deviceId(v.deviceId());
        res.notes(v.notes());
        return res;
    }

    private static StockTakeReviewResponse toReviewResponse(StockTakeReviewView v) {
        List<StockTakeReviewLine> lines = v.lines().stream()
                .map(l -> {
                    StockTakeReviewLine line = new StockTakeReviewLine(
                            l.skuId(),
                            l.skuCode(),
                            l.skuNameEn(),
                            StockTakeReviewLine.ConditionEnum.fromValue(l.condition()),
                            l.expectedQty(),
                            l.countedQty(),
                            l.varianceQty(),
                            l.unitCost(),
                            l.varianceValue(),
                            StockTakeReviewLine.StatusEnum.fromValue(l.status()));
                    line.skuNameSi(l.skuNameSi());
                    line.skuNameTa(l.skuNameTa());
                    line.barcode(l.barcode());
                    line.batchId(l.batchId());
                    line.batchNo(l.batchNo());
                    line.expiryDate(l.expiryDate());
                    return line;
                })
                .toList();

        return new StockTakeReviewResponse(
                v.sessionId(),
                v.locationId(),
                v.status(),
                v.totalExpectedLots(),
                v.totalCountedLots(),
                v.matchedLots(),
                v.discrepantLots(),
                v.uncountedLots(),
                v.totalVarianceQty(),
                v.totalVarianceValue(),
                lines);
    }
}
