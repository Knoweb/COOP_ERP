package lk.coopfed.knoweb.m2catalogue.api;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * Seam for querying retail price list contents for assortment maintenance (doc 22 section 3.8; doc 23).
 * M2 publishes the question and M3 answers it by implementing this interface.
 */
public interface RetailPriceListAssortmentQuery {

    /**
     * Return all SKU IDs present on active, published RETAIL price lists for the owning entity.
     */
    default List<UUID> skusOnRetailList(UUID ownerEntityId, ScopeContext scope) {
        return List.of();
    }
}
