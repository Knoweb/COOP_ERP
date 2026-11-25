package lk.coopfed.knoweb.m5inventory.internal.reorder;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.api.StockMoved;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Consumes stock movements to evaluate inventory levels against configured reorder thresholds (PGL-M2-01).
 *
 * <p>Runs in the event owner's OWN scope. When an item has dropped to or below its reorder level,
 * an alert is triggered once and subsequent stock reductions are deduplicated. When stock rises
 * back above the reorder level, the alert is automatically re-armed.
 */
@Component
public class LowStockStockMovedConsumer {

    private static final Logger log = LoggerFactory.getLogger(LowStockStockMovedConsumer.class);
    public static final String CONSUMER = "m5.low_stock_evaluator";

    private final LowStockEvaluationHandler evaluationHandler;

    public LowStockStockMovedConsumer(LowStockEvaluationHandler evaluationHandler) {
        this.evaluationHandler = evaluationHandler;
    }

    @EventConsumer(
            types = {StockMoved.TYPE},
            consumer = CONSUMER)
    public void onStockMoved(JsonNode payload, ScopeContext scope) {
        UUID locationId = uuid(payload, "locationId");
        if (locationId == null) {
            locationId = uuid(payload, "location_id");
        }
        UUID skuId = uuid(payload, "skuId");
        if (skuId == null) {
            skuId = uuid(payload, "sku_id");
        }
        if (locationId == null || skuId == null) {
            return;
        }

        try {
            evaluationHandler.evaluateSku(locationId, skuId, scope);
        } catch (RuntimeException e) {
            log.error("Failed to evaluate low stock on StockMoved for location {} sku {}", locationId, skuId, e);
            throw e;
        }
    }

    public void onStockMoved(StockMoved event, ScopeContext scope) {
        if (event == null || event.locationId() == null || event.skuId() == null) {
            return;
        }
        try {
            evaluationHandler.evaluateSku(event.locationId(), event.skuId(), scope);
        } catch (RuntimeException e) {
            log.error(
                    "Failed to evaluate low stock on StockMoved for location {} sku {}",
                    event.locationId(),
                    event.skuId(),
                    e);
            throw e;
        }
    }

    private static UUID uuid(JsonNode node, String field) {
        JsonNode val = node.get(field);
        return val != null && !val.isNull() ? UUID.fromString(val.asText()) : null;
    }
}
