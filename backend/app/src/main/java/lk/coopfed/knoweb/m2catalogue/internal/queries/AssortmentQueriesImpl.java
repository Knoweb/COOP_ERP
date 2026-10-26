package lk.coopfed.knoweb.m2catalogue.internal.queries;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.internal.assortment.AssortmentStore;
import lk.coopfed.knoweb.m2catalogue.query.AssortmentQueries;
import lk.coopfed.knoweb.m2catalogue.query.LocationAssortmentView;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of AssortmentQueries (22A section 5, section 7.2).
 */
@Component
@Transactional(readOnly = true)
public class AssortmentQueriesImpl implements AssortmentQueries {

    private final AssortmentStore store;

    public AssortmentQueriesImpl(AssortmentStore store) {
        this.store = store;
    }

    @Override
    public List<LocationAssortmentView> getAssortment(UUID locationId, ScopeContext scope) {
        if (locationId == null) {
            return List.of();
        }
        return store.findByLocation(locationId).stream()
                .map(r -> new LocationAssortmentView(
                        r.locationId(), r.skuId(), r.reasons(), r.sellThrough(), r.since(), r.ownerEntityId()))
                .toList();
    }

    @Override
    public Optional<LocationAssortmentView> getAssortmentItem(UUID locationId, UUID skuId, ScopeContext scope) {
        if (locationId == null || skuId == null) {
            return Optional.empty();
        }
        return store.get(locationId, skuId)
                .map(r -> new LocationAssortmentView(
                        r.locationId(), r.skuId(), r.reasons(), r.sellThrough(), r.since(), r.ownerEntityId()));
    }
}
