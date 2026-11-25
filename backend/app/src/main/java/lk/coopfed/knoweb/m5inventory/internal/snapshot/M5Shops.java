package lk.coopfed.knoweb.m5inventory.internal.snapshot;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Target;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import org.springframework.stereotype.Component;

/**
 * Resolves target shop locations for M5 inventory change-log fan-out (doc 32 section 5.2).
 *
 * <p>Uses M1's {@link PartyQueries#listChangeLogShops} running within the consumer's own scope
 * against the {@code party.change_log_shops} SECURITY DEFINER function. Does not construct
 * synthetic security scopes (compliant with PR #345 architecture rules).
 */
@Component
public class M5Shops {

    private final PartyQueries parties;

    M5Shops(PartyQueries parties) {
        this.parties = parties;
    }

    /** Whether the location is an enrolled/active shop of the entity (not a warehouse or office). */
    public boolean isShop(UUID entityId, UUID locationId, ScopeContext consumerScope) {
        if (entityId == null || locationId == null) {
            return false;
        }
        return parties.listChangeLogShops(entityId, consumerScope).stream()
                .anyMatch(shop -> locationId.equals(shop.locationId()));
    }

    /** Every shop of the given entity. */
    public List<Target> of(UUID entityId, ScopeContext consumerScope) {
        if (entityId == null) {
            return List.of();
        }
        return targets(entityId, consumerScope);
    }

    /** Every shop of the federation; consumerScope must be the Federation's OWN scope. */
    public List<Target> all(ScopeContext consumerScope) {
        return targets(null, consumerScope);
    }

    private List<Target> targets(UUID entityId, ScopeContext consumerScope) {
        return parties.listChangeLogShops(entityId, consumerScope).stream()
                .map(shop -> new Target(shop.ownerEntityId(), shop.locationId()))
                .toList();
    }
}
