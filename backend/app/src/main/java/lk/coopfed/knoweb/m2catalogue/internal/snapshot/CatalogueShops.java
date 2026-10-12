package lk.coopfed.knoweb.m2catalogue.internal.snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Target;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.ScopedExecution;
import lk.coopfed.knoweb.m1party.query.LocationFilter;
import lk.coopfed.knoweb.m1party.query.LocationPage;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import org.springframework.stereotype.Component;

/**
 * The shops an M2 catalogue change is recorded at for their tills (doc 32 section 5.2).
 *
 * <ul>
 *   <li>{@link #of(UUID)}: the shops of one entity, listed in that entity's OWN scope.
 *   <li>{@link #all()}: every shop of the federation, listed in the kernel's federation view.
 * </ul>
 *
 * <p>Every shop, whatever its status (PLANNED, ONBOARDING, ACTIVE or DORMANT; m1party V0001 has no
 * closed status and a location is never deleted): a till enrols at a shop before the shop is
 * activated (RegisterTillPosition accepts a PLANNED or ONBOARDING shop, CR-21A-5) and a dormant
 * shop's till keeps its version, and a delta carries only what was recorded at the location. A
 * change left out at such a shop would never reach its till (wave 4 TD-01).
 *
 * <p>The scopes come from {@link ScopedExecution} and the list runs in a transaction of its own
 * ({@code inOwnTransaction}, on a connection of its own), so the consumer's connection keeps the
 * consumer's scope for the change-log write that follows; nothing here builds a scope by hand.
 */
@Component
public class CatalogueShops {

    private static final int PAGE = 100;
    private static final String LOCATION_TYPE_SHOP = "SHOP";

    private final PartyQueries parties;
    private final ScopedExecution execution;

    CatalogueShops(PartyQueries parties, ScopedExecution execution) {
        this.parties = parties;
        this.execution = execution;
    }

    /** Every shop of the given entity, in any status. */
    public List<Target> of(UUID entityId) {
        if (entityId == null) {
            return List.of();
        }
        ScopeContext entityWide = execution.own(entityId);
        return execution.inOwnTransaction(entityWide, () -> list(entityId, entityWide));
    }

    /** Every shop of the federation, in any status. */
    public List<Target> all() {
        ScopeContext fedView = execution.federationView();
        return execution.inOwnTransaction(fedView, () -> list(null, fedView));
    }

    private List<Target> list(UUID entityId, ScopeContext scope) {
        List<Target> targets = new ArrayList<>();
        UUID cursor = null;
        while (true) {
            // A null status: every status (TD-01).
            LocationPage page =
                    parties.listLocations(new LocationFilter(null, LOCATION_TYPE_SHOP, entityId, cursor, PAGE), scope);
            for (LocationView shop : page.items()) {
                targets.add(new Target(shop.ownerEntityId(), shop.locationId()));
            }
            if (page.nextCursor() == null || page.items().isEmpty()) {
                return List.copyOf(targets);
            }
            cursor = UUID.fromString(page.nextCursor());
        }
    }
}
