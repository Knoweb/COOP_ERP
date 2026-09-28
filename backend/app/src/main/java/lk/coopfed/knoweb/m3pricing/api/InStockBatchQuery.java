package lk.coopfed.knoweb.m3pricing.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The question M3 asks of M5 (23A section 2: "InStockBatches(locationIds, skuId)"): the batches of
 * a SKU with stock at some locations. M3 is master data and cannot read M5's query (the layer
 * rule), so M3 publishes the question and M5 answers it, as M5 answers M2's InventoryLotQuery.
 * Used by the RETAIL authoring check (a retail price above the lowest printed MRP in stock at the
 * society's locations is refused) and by the retail price resolution at central.
 */
public interface InStockBatchQuery {

    /** The GOOD lots with stock of the SKU at any of the locations, as the caller's scope sees them. */
    List<InStockBatch> inStockBatches(Collection<UUID> locationIds, UUID skuId, ScopeContext scope);

    /** One lot: its batch, where it is, how much, and when it expires. */
    record InStockBatch(UUID batchId, UUID locationId, BigDecimal onHand, LocalDate expiryDate) {}
}
