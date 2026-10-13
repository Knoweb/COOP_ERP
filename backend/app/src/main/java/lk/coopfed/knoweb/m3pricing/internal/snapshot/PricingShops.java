package lk.coopfed.knoweb.m3pricing.internal.snapshot;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Target;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import org.springframework.stereotype.Component;

/**
 * Resolves target shop locations for M3 pricing change-log fan-out (doc 32 section 5.2).
 *
 * <ul>
 *   <li>{@link #of(UUID, ScopeContext)}: every shop of one entity (retail lists, discount rules, entity MRP policies).
 *   <li>{@link #all(ScopeContext)}: every shop of the federation (control prices, federation MRP defaults).
 * </ul>
 *
 * <p>The lookup runs in the consumer's own scope, on the consumer's connection: M1's
 * {@code PartyQueries.listChangeLogShops} asks the SECURITY DEFINER function
 * {@code party.change_log_shops} (m1party V0017), which decides from the scope what it may answer
 * (the Federation every shop, an entity its own). No scope is built here (wave 4 TD-03).
 */
@Component
public class PricingShops {

    private final PartyQueries parties;

    PricingShops(PartyQueries parties) {
        this.parties = parties;
    }

    /** Every shop of the given entity, in any status. */
    public List<Target> of(UUID entityId, ScopeContext consumerScope) {
        if (entityId == null) {
            return List.of();
        }
        return targets(entityId, consumerScope);
    }

    /** Every shop of the federation, in any status; the consumer's scope must be the Federation's. */
    public List<Target> all(ScopeContext consumerScope) {
        return targets(null, consumerScope);
    }

    private List<Target> targets(UUID entityId, ScopeContext consumerScope) {
        return parties.listChangeLogShops(entityId, consumerScope).stream()
                .map(shop -> new Target(shop.ownerEntityId(), shop.locationId()))
                .toList();
    }
}
