package lk.coopfed.knoweb.m5inventory.web;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.api.ConfigureReorderControl;
import lk.coopfed.knoweb.m5inventory.api.DeleteReorderControl;
import lk.coopfed.knoweb.m5inventory.api.EvaluateLowStock;
import lk.coopfed.knoweb.m5inventory.internal.reorder.LowStockEvaluationResult;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.ReorderControlView;
import lk.coopfed.knoweb.m5inventory.web.generated.ConfigureReorderControlRequest;
import lk.coopfed.knoweb.m5inventory.web.generated.EvaluateLowStockResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.ReorderControlResponse;
import lk.coopfed.knoweb.m5inventory.web.generated.ReorderControlsApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for maintaining reorder controls and triggering low-stock evaluation (PGL-M2-01).
 */
@RestController
public class ReorderControlsController implements ReorderControlsApi {

    private final Handles<ConfigureReorderControl, UUID> configureHandler;
    private final Handles<DeleteReorderControl, Void> deleteHandler;
    private final Handles<EvaluateLowStock, LowStockEvaluationResult> evaluateHandler;
    private final InventoryQueries queries;
    private final CurrentScope currentScope;

    public ReorderControlsController(
            Handles<ConfigureReorderControl, UUID> configureHandler,
            Handles<DeleteReorderControl, Void> deleteHandler,
            Handles<EvaluateLowStock, LowStockEvaluationResult> evaluateHandler,
            InventoryQueries queries,
            CurrentScope currentScope) {
        this.configureHandler = configureHandler;
        this.deleteHandler = deleteHandler;
        this.evaluateHandler = evaluateHandler;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<List<ReorderControlResponse>> listReorderControls(UUID locationId, UUID skuId) {
        ScopeContext scope = currentScope.get();
        List<ReorderControlView> views = queries.reorderControls(locationId, skuId, scope);
        List<ReorderControlResponse> responses =
                views.stream().map(ReorderControlsController::toResponse).toList();
        return ResponseEntity.ok(responses);
    }

    @Override
    public ResponseEntity<ReorderControlResponse> configureReorderControl(
            String idempotencyKey, ConfigureReorderControlRequest request) {
        ScopeContext scope = currentScope.get();
        configureHandler.handle(
                new ConfigureReorderControl(
                        request.getLocationId(),
                        request.getSkuId(),
                        request.getReorderLevel(),
                        request.getReorderQty(),
                        request.getMaxQty()),
                scope);

        List<ReorderControlView> views = queries.reorderControls(request.getLocationId(), request.getSkuId(), scope);
        ReorderControlView view = views.getFirst();
        return ResponseEntity.ok(toResponse(view));
    }

    @Override
    public ResponseEntity<Void> deleteReorderControl(String idempotencyKey, UUID locationId, UUID skuId) {
        ScopeContext scope = currentScope.get();
        deleteHandler.handle(new DeleteReorderControl(locationId, skuId), scope);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<EvaluateLowStockResponse> evaluateLowStock(
            String idempotencyKey, UUID locationId, UUID skuId) {
        ScopeContext scope = currentScope.get();
        LowStockEvaluationResult result = evaluateHandler.handle(new EvaluateLowStock(locationId, skuId), scope);
        return ResponseEntity.ok(new EvaluateLowStockResponse(result.evaluatedCount(), result.alertsTriggeredCount()));
    }

    private static ReorderControlResponse toResponse(ReorderControlView view) {
        ReorderControlResponse response = new ReorderControlResponse(
                view.reorderControlId(),
                view.ownerEntityId(),
                view.locationId(),
                view.skuId(),
                view.reorderLevel(),
                view.reorderQty(),
                view.alertTriggered());
        response.setMaxQty(view.maxQty());
        response.setLastAlertedAt(view.lastAlertedAt());
        response.setLastAlertQty(view.lastAlertQty());
        response.setCurrentOnHandQty(view.currentOnHandQty());
        response.setCurrentAvailableQty(view.currentAvailableQty());
        return response;
    }
}
