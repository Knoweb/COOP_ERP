package lk.coopfed.knoweb.m5inventory.internal.availability;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.api.InventoryCandidateQuery;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import org.springframework.stereotype.Component;

/**
 * M5's implementation of M3's {@link InventoryCandidateQuery} question (PGL-M1-07; layer rule ADR-05).
 * Delegates to M5's authoritative {@link InventoryQueries#nearExpiryLots} and
 * {@link InventoryQueries#slowMovingStock} without exposing private inventory tables to M3.
 */
@Component
class InventoryCandidatesForPricing implements InventoryCandidateQuery {

    private final InventoryQueries inventory;

    InventoryCandidatesForPricing(InventoryQueries inventory) {
        this.inventory = inventory;
    }

    @Override
    public List<NearExpiryCandidate> nearExpiryCandidates(UUID locationId, ScopeContext scope) {
        return inventory.nearExpiryLots(locationId, null, null, scope).stream()
                .map(lot -> new NearExpiryCandidate(
                        lot.stockLotId(),
                        lot.locationId(),
                        lot.skuId(),
                        lot.batchId(),
                        lot.batchNo(),
                        lot.expiryDate(),
                        lot.daysToExpiry(),
                        lot.qtyOnHand(),
                        lot.unitCost(),
                        lot.urgencyTier(),
                        lot.alertActive()))
                .toList();
    }

    @Override
    public List<SlowMovingCandidate> slowMovingCandidates(UUID locationId, ScopeContext scope) {
        return inventory.slowMovingStock(locationId, null, null, scope).stream()
                .map(item -> new SlowMovingCandidate(
                        item.locationId(),
                        item.skuId(),
                        item.qtyOnHand(),
                        item.unitCost(),
                        item.daysWithoutSale(),
                        item.lastSaleDate(),
                        item.salesInWindow(),
                        item.averageDailySales(),
                        item.daysOfSupply(),
                        item.classification(),
                        item.alertActive()))
                .toList();
    }
}
