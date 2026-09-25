package lk.coopfed.knoweb.m2catalogue.query;

import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

public interface CatalogueQueries {

    Optional<SkuView> getSku(UUID skuId, ScopeContext scope);

    SkuPage listSkus(SkuFilter filter, ScopeContext scope);

    SkuPage searchSku(SkuFilter filter, ScopeContext scope);
}
