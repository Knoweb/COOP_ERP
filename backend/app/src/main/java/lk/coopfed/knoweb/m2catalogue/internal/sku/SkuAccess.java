package lk.coopfed.knoweb.m2catalogue.internal.sku;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * The "owner" guard of 22A section 6 for the aggregates that hang off a SKU (conversions and
 * barcodes): the caller must be in an entity-wide OWN scope and own the SKU. It reads under the
 * caller's scope, so a SKU another entity keeps LOCAL is not found rather than refused.
 */
@Component
public class SkuAccess {

    private final SkuRepository repository;

    SkuAccess(SkuRepository repository) {
        this.repository = repository;
    }

    /** The SKU when the caller owns it; m2.sku.not_found or m2.sku.owner_mismatch otherwise. */
    public SkuIdentity requireOwned(UUID skuId, ScopeContext scope) {
        return identity(SkuGuards.requireOwned(repository, skuId, scope));
    }

    /**
     * The SKU when the caller can see it: its own, or a SHARED one (the shared_read policy). An
     * INTERNAL barcode is an entity's own sticker on any item it sells (doc 22 section 3.3), so
     * it needs the item visible, not owned.
     */
    public SkuIdentity requireVisible(UUID skuId, ScopeContext scope) {
        SkuGuards.requireEntityWideScope(scope);

        if (skuId == null) {
            throw new ProblemException("request.field.required", Map.of("field", "skuId"));
        }

        return findVisible(skuId, scope)
                .orElseThrow(() -> new ProblemException("m2.sku.not_found", Map.of("skuId", skuId)));
    }

    public Optional<SkuIdentity> findVisible(UUID skuId, ScopeContext scope) {
        if (skuId == null || scope == null || !scope.hasActiveScope()) {
            return Optional.empty();
        }
        return repository.findById(skuId).map(SkuAccess::identity);
    }

    private static SkuIdentity identity(Sku sku) {
        return new SkuIdentity(sku.getId(), sku.ownerEntityId(), sku.status(), sku.baseUomCode(), sku.soldByWeight());
    }
}
