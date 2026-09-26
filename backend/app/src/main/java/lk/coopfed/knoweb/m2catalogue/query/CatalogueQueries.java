package lk.coopfed.knoweb.m2catalogue.query;

import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

public interface CatalogueQueries {

    Optional<SkuView> getSku(UUID skuId, ScopeContext scope);

    SkuPage listSkus(SkuFilter filter, ScopeContext scope);

    SkuPage searchSku(SkuFilter filter, ScopeContext scope);

    /**
     * Resolves a scanned code to a SKU, its unit and, when it can, a batch (22A section 7,
     * LookupByBarcode): the exact ACTIVE registry row first, then the GTIN with the lot, then an
     * INTERNAL code of the caller's own entity. Empty when nothing visible in the scope matches.
     */
    Optional<LookupResult> lookupByBarcode(BarcodeLookup lookup, ScopeContext scope);
}
