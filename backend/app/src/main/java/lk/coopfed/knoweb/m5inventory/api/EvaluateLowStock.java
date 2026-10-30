package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/**
 * Evaluate inventory against reorder levels for a location, and optionally a specific SKU (PGL-M2-01).
 *
 * @param locationId location whose stock to evaluate
 * @param skuId optional specific item; null evaluates all configured reorder controls at the location
 */
public record EvaluateLowStock(UUID locationId, UUID skuId) {

    public EvaluateLowStock(UUID locationId) {
        this(locationId, null);
    }
}
