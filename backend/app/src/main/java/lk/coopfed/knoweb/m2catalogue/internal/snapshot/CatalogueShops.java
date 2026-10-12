package lk.coopfed.knoweb.m2catalogue.internal.snapshot;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Target;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import org.springframework.stereotype.Component;

/**
 * The shops an M2 catalogue change is recorded at for their tills (doc 32 section 5.2).
 *
 * <ul>
 *   <li>{@link #of(UUID, ScopeContext)}: the shops of one entity.
 *   <li>{@link #all(ScopeContext)}: every shop of the federation (a SHARED item, the Federation's).
 * </ul>
 *
 * <p>Every shop, whatever its status (PLANNED, ONBOARDING, ACTIVE or DORMANT; m1party V0001 has no
 * closed status and a location is never deleted): a till enrols at a shop before the shop is
 * activated (RegisterTillPosition accepts a PLANNED or ONBOARDING shop, CR-21A-5) and a dormant
 * shop's till keeps its version, and a delta carries only what was recorded at the location. A
 * change left out at such a shop would never reach its till (wave 4 TD-01).
 *
 * <p>The lookup runs in the consumer's own scope, on the consumer's connection: M1's
 * {@code PartyQueries.listChangeLogShops} asks the SECURITY DEFINER function
 * {@code party.change_log_shops} (m1party V0017), which decides from the scope what it may answer
 * (the Federation every shop, an entity its own). No scope is built here (wave 4 TD-03).
 */
@Component
public class CatalogueShops {

    private final PartyQueries parties;

    CatalogueShops(PartyQueries parties) {
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
