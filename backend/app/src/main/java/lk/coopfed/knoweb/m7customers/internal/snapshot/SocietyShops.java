package lk.coopfed.knoweb.m7customers.internal.snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.LocationFilter;
import lk.coopfed.knoweb.m1party.query.LocationPage;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shops of a society, for the change-log fan-out (wave 2, M7CR-14; {@code
 * 2026-10-06-wave2-m7-credit-book.md} (6)). The platform pair's question was whether a central
 * event published inside a shop-scoped consumer carries the shop: it does ({@code OutboxWriter}
 * reads {@code kernel.scope_location()}), so the dispatcher hands the fan-out a scope at that one
 * shop, where M1's {@code own_read} on {@code party.location} shows that shop alone. The decision's
 * answer is "an entity-wide PartyQueries call": this reads the shops in the OWN scope of the same
 * society, entity-wide, in a transaction of its own (the kernel's scope aspect applies the scope
 * on the argument of a public {@code @Transactional} method, as it does for every handler), so the
 * consumer's own connection keeps the scope the dispatcher gave it. Nothing is borrowed from
 * another entity: the society is the event's owner.
 */
@Component
public class SocietyShops {

    private static final int PAGE = 200;

    private final PartyQueries parties;

    SocietyShops(PartyQueries parties) {
        this.parties = parties;
    }

    /** The shop ids of the society, through {@link #list(ScopeContext)} in that society's entity-wide scope. */
    public List<UUID> of(UUID society, ScopeContext consumerScope) {
        Scope wide = new Scope(society, null);
        ScopeContext entityWide = new ScopeContext(
                null,
                consumerScope.deviceId(),
                society,
                List.of(wide),
                wide,
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.ENGLISH,
                consumerScope.correlationId());
        return list(entityWide);
    }

    /** Public and {@code @Transactional} with the scope as its argument: that is what the scope aspect applies. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<UUID> list(ScopeContext entityWide) {
        List<UUID> shops = new ArrayList<>();
        UUID cursor = null;
        while (true) {
            LocationPage page = parties.listLocations(new LocationFilter(null, "SHOP", null, cursor, PAGE), entityWide);
            for (LocationView shop : page.items()) {
                shops.add(shop.locationId());
            }
            if (page.nextCursor() == null || page.items().isEmpty()) {
                return List.copyOf(shops);
            }
            cursor = UUID.fromString(page.nextCursor());
        }
    }
}
