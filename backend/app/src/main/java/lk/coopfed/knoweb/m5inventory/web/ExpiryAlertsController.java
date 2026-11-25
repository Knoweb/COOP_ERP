package lk.coopfed.knoweb.m5inventory.web;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.api.EvaluateExpiryAlerts;
import lk.coopfed.knoweb.m5inventory.api.ExpiryAlertScanResult;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.NearExpiryLotView;
import lk.coopfed.knoweb.m5inventory.query.SlowMovingStockView;
import lk.coopfed.knoweb.m5inventory.web.generated.ExpiryAlertItemResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.ExpiryAlertScanResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.ExpiryAlertsApi;
import lk.coopfed.knoweb.m5inventory.web.generated.SlowMovingStockResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for near-expiry alert feeds, slow-moving stock detection, and on-demand alert scans (PGL-M2-07).
 */
@RestController
public class ExpiryAlertsController implements ExpiryAlertsApi {

    private final Handles<EvaluateExpiryAlerts, ExpiryAlertScanResult> scanHandler;
    private final InventoryQueries queries;
    private final CurrentScope currentScope;

    public ExpiryAlertsController(
            Handles<EvaluateExpiryAlerts, ExpiryAlertScanResult> scanHandler,
            InventoryQueries queries,
            CurrentScope currentScope) {
        this.scanHandler = scanHandler;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<List<ExpiryAlertItemResponse>> getExpiryAlertFeed(
            UUID locationId, Integer windowDays, String tier) {
        ScopeContext scope = currentScope.get();
        List<NearExpiryLotView> lots = queries.nearExpiryLots(locationId, windowDays, tier, scope);
        List<ExpiryAlertItemResponse> responses =
                lots.stream().map(ExpiryAlertsController::toResponse).toList();
        return ResponseEntity.ok(responses);
    }

    @Override
    public ResponseEntity<List<SlowMovingStockResponse>> getSlowMovingStock(
            UUID locationId, Integer evaluationDays, Integer thresholdDaysOfSupply) {
        ScopeContext scope = currentScope.get();
        List<SlowMovingStockView> items =
                queries.slowMovingStock(locationId, evaluationDays, thresholdDaysOfSupply, scope);
        List<SlowMovingStockResponse> responses =
                items.stream().map(ExpiryAlertsController::toResponse).toList();
        return ResponseEntity.ok(responses);
    }

    @Override
    public ResponseEntity<ExpiryAlertScanResponse> scanExpiryAlerts(String idempotencyKey, UUID locationId) {
        ScopeContext scope = currentScope.get();
        ExpiryAlertScanResult result = scanHandler.handle(new EvaluateExpiryAlerts(locationId), scope);
        return ResponseEntity.ok(new ExpiryAlertScanResponse(
                result.nearExpiryAlertsEmitted(), result.slowMovingAlertsEmitted(), result.totalEvaluated()));
    }

    private static ExpiryAlertItemResponse toResponse(NearExpiryLotView view) {
        ExpiryAlertItemResponse response = new ExpiryAlertItemResponse();
        response.setStockLotId(view.stockLotId());
        response.setLocationId(view.locationId());
        response.setSkuId(view.skuId());
        response.setBatchId(view.batchId());
        response.setBatchNo(view.batchNo());
        response.setExpiryDate(view.expiryDate());
        response.setDaysToExpiry(view.daysToExpiry());
        response.setQtyOnHand(view.qtyOnHand());
        response.setUnitCost(view.unitCost());
        response.setUrgencyTier(ExpiryAlertItemResponse.UrgencyTierEnum.fromValue(view.urgencyTier()));
        response.setAlertActive(view.alertActive());
        return response;
    }

    private static SlowMovingStockResponse toResponse(SlowMovingStockView view) {
        SlowMovingStockResponse response = new SlowMovingStockResponse();
        response.setLocationId(view.locationId());
        response.setSkuId(view.skuId());
        response.setQtyOnHand(view.qtyOnHand());
        response.setUnitCost(view.unitCost());
        response.setDaysWithoutSale(view.daysWithoutSale());
        response.setLastSaleDate(view.lastSaleDate());
        response.setSalesInWindow(view.salesInWindow());
        response.setAverageDailySales(view.averageDailySales());
        response.setDaysOfSupply(view.daysOfSupply());
        response.setClassification(SlowMovingStockResponse.ClassificationEnum.fromValue(view.classification()));
        response.setAlertActive(view.alertActive());
        return response;
    }
}
