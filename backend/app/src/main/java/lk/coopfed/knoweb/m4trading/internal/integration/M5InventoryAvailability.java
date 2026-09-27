package lk.coopfed.knoweb.m4trading.internal.integration;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.LocationFilter;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m4trading.api.InventoryAvailability;
import lk.coopfed.knoweb.m5inventory.query.Availability;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import org.springframework.stereotype.Component;

/**
 * M4's availability question answered by M5 (24A section 2: "Availability(seller locations,
 * skus)"; M5-04, #154): the GOOD stock of each item across the seller's active warehouses, less
 * what issued delivery notes reserved, as M5 computes it. Replaced the register's demo answer in
 * M4-05. Row-level security lets only the seller read its own lots and locations, so the answer is
 * the caller's own availability; a buyer asking about a seller gets nothing (a masked read for
 * buyers is deferred).
 */
@Component
class M5InventoryAvailability implements InventoryAvailability {

    static final String WAREHOUSE = "WAREHOUSE";
    static final String ACTIVE = "ACTIVE";

    private final PartyQueries parties;
    private final InventoryQueries inventory;

    M5InventoryAvailability(PartyQueries parties, InventoryQueries inventory) {
        this.parties = parties;
        this.inventory = inventory;
    }

    @Override
    public Map<UUID, BigDecimal> availability(UUID sellerEntityId, Collection<UUID> skuIds, ScopeContext scope) {
        Map<UUID, BigDecimal> available = new LinkedHashMap<>();
        if (scope == null || sellerEntityId == null || !sellerEntityId.equals(scope.entityId()) || skuIds.isEmpty()) {
            return available;
        }
        List<UUID> warehouses = parties
                .listLocations(new LocationFilter(ACTIVE, WAREHOUSE, sellerEntityId, null, null), scope)
                .items()
                .stream()
                .map(LocationView::locationId)
                .toList();
        if (warehouses.isEmpty()) {
            return available;
        }
        for (Availability row : inventory.availability(warehouses, skuIds, scope)) {
            available.merge(row.skuId(), row.available(), BigDecimal::add);
        }
        return available;
    }
}
