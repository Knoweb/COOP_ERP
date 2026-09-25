package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;

/**
 * Inventory seam used by SKU lifecycle guards.
 *
 * M5 supplies the real implementation when inventory lots exist.
 */
public interface InventoryLotQuery {

    boolean hasAnyLot(UUID skuId);
}
