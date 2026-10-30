package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Configure reorder level, reorder quantity and maximum stocking quantity for an SKU at a location (PGL-M2-01).
 *
 * @param locationId warehouse or shop location
 * @param skuId item identity from catalogue
 * @param reorderLevel threshold quantity: stock at or below this level triggers replenishment
 * @param reorderQty suggested quantity to reorder
 * @param maxQty target capacity or ceiling quantity (optional; if given, must be >= reorderLevel)
 */
public record ConfigureReorderControl(
        UUID locationId, UUID skuId, BigDecimal reorderLevel, BigDecimal reorderQty, BigDecimal maxQty) {

    public ConfigureReorderControl(UUID locationId, UUID skuId, BigDecimal reorderLevel, BigDecimal reorderQty) {
        this(locationId, skuId, reorderLevel, reorderQty, null);
    }
}
