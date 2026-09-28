package lk.coopfed.knoweb.m5inventory.internal.availability;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.api.InStockBatchQuery;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import org.springframework.stereotype.Component;

/**
 * M5's answer to M3's question (23A section 2, "InStockBatches(locationIds, skuId)"; 25A section
 * 10, M5-04: "replace the M2/M3/M4 stubs"). M3 publishes {@link InStockBatchQuery} and cannot call
 * M5 (the layer rule: master data does not read transactions), so M5 implements it, as it answers
 * M2's InventoryLotQuery. The lots are those {@link InventoryQueries#inStockBatches} returns: GOOD,
 * with stock, in the caller's scope.
 */
@Component
class InStockBatchesForPricing implements InStockBatchQuery {

    private final InventoryQueries inventory;

    InStockBatchesForPricing(InventoryQueries inventory) {
        this.inventory = inventory;
    }

    @Override
    public List<InStockBatch> inStockBatches(Collection<UUID> locationIds, UUID skuId, ScopeContext scope) {
        return inventory.inStockBatches(locationIds, skuId, scope).stream()
                .map(lot -> new InStockBatch(lot.batchId(), lot.locationId(), lot.qtyOnHand(), lot.expiryDate()))
                .toList();
    }
}
