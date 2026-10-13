package lk.coopfed.knoweb.m3pricing.internal.snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Target;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.LocationFilter;
import lk.coopfed.knoweb.m1party.query.LocationPage;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves target shop locations for M3 pricing change-log fan-out.
 *
 * <p>Uses {@link PartyQueries} with explicit scope contexts to discover active shops:
 * <ul>
 *   <li>{@link #of(UUID, ScopeContext)}: an entity-wide OWN scope to discover active shops of that MPCS entity.
 *   <li>{@link #all(ScopeContext)}: a FEDERATION_VIEW scope to discover all active shops across the federation.
 * </ul>
 * Restores the caller's connection scope settings in a finally block so subsequent change-log writes
 * retain the expected OWN policy class without session-scope leakage.
 */
@Component
public class PricingShops {

    private static final int PAGE = 100;
    private static final String LOCATION_TYPE_SHOP = "SHOP";
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final PartyQueries parties;
    private final JdbcTemplate jdbc;

    PricingShops(PartyQueries parties, JdbcTemplate jdbc) {
        this.parties = parties;
        this.jdbc = jdbc;
    }

    /** All active shops of the given entity. */
    public List<Target> of(UUID entityId, ScopeContext consumerScope) {
        if (entityId == null) {
            return List.of();
        }
        Scope wide = new Scope(entityId, null);
        ScopeContext entityWide = new ScopeContext(
                null,
                consumerScope == null ? null : consumerScope.deviceId(),
                entityId,
                List.of(wide),
                wide,
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.ENGLISH,
                consumerScope == null ? null : consumerScope.correlationId());
        try {
            return listEntityShops(entityId, entityWide);
        } finally {
            restoreScope(consumerScope);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<Target> listEntityShops(UUID entityId, ScopeContext entityWide) {
        List<Target> targets = new ArrayList<>();
        UUID cursor = null;
        while (true) {
            LocationPage page = parties.listLocations(
                    new LocationFilter(STATUS_ACTIVE, LOCATION_TYPE_SHOP, entityId, cursor, PAGE), entityWide);
            for (LocationView shop : page.items()) {
                targets.add(new Target(entityId, shop.locationId()));
            }
            if (page.nextCursor() == null || page.items().isEmpty()) {
                return List.copyOf(targets);
            }
            cursor = UUID.fromString(page.nextCursor());
        }
    }

    /** All active shops across the entire federation. */
    public List<Target> all(ScopeContext consumerScope) {
        Scope wide = new Scope(new UUID(0, 0), null);
        ScopeContext fedView = new ScopeContext(
                null,
                consumerScope == null ? null : consumerScope.deviceId(),
                wide.entityId(),
                List.of(wide),
                wide,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                Locale.ENGLISH,
                consumerScope == null ? null : consumerScope.correlationId());
        try {
            return listAllShops(fedView);
        } finally {
            restoreScope(consumerScope);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<Target> listAllShops(ScopeContext fedView) {
        List<Target> targets = new ArrayList<>();
        UUID cursor = null;
        while (true) {
            LocationPage page = parties.listLocations(
                    new LocationFilter(STATUS_ACTIVE, LOCATION_TYPE_SHOP, null, cursor, PAGE), fedView);
            for (LocationView shop : page.items()) {
                targets.add(new Target(shop.ownerEntityId(), shop.locationId()));
            }
            if (page.nextCursor() == null || page.items().isEmpty()) {
                return List.copyOf(targets);
            }
            cursor = UUID.fromString(page.nextCursor());
        }
    }

    private void restoreScope(ScopeContext scope) {
        if (scope == null) {
            jdbc.queryForList("select set_config('app.scope_class', 'OWN', true)");
            return;
        }
        String policyClass =
                scope.policyClass() == null ? "OWN" : scope.policyClass().name();
        String userId = scope.userId() == null ? "" : scope.userId().toString();
        String correlationId =
                scope.correlationId() == null ? "" : scope.correlationId().toString();
        String entityId = scope.entityId() == null ? "" : scope.entityId().toString();
        String locationId = scope.locationId() == null ? "" : scope.locationId().toString();
        jdbc.queryForList(
                "select set_config('app.user_id', ?, true), "
                        + "set_config('app.correlation_id', ?, true), "
                        + "set_config('app.scope_entity_id', ?, true), "
                        + "set_config('app.scope_location_id', ?, true), "
                        + "set_config('app.scope_class', ?, true), "
                        + "set_config('app.granted_entities', '{}', true)",
                userId,
                correlationId,
                entityId,
                locationId,
                policyClass);
    }
}
