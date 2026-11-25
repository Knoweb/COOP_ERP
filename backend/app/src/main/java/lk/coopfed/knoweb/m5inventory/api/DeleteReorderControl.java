package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/**
 * Delete a configured reorder control for an SKU at a location (PGL-M2-01).
 *
 * @param locationId warehouse or shop location
 * @param skuId item identity from catalogue
 */
public record DeleteReorderControl(UUID locationId, UUID skuId) {}
