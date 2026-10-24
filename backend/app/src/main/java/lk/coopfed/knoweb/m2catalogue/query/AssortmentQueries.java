package lk.coopfed.knoweb.m2catalogue.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * Published assortment queries (22A section 5, section 7.2; doc 22 section 3.8).
 */
public interface AssortmentQueries {

    /** GetAssortment: all assortment rows of a location visible in the caller's scope. */
    List<LocationAssortmentView> getAssortment(UUID locationId, ScopeContext scope);

    /** Checks if a specific SKU is in the location's assortment and returns its status. */
    Optional<LocationAssortmentView> getAssortmentItem(UUID locationId, UUID skuId, ScopeContext scope);
}
