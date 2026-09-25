package lk.coopfed.knoweb.m1party.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

public interface PartyQueries {

    Optional<EntityView> getEntity(UUID entityId, ScopeContext scope);

    EntityPage listEntities(EntityFilter filter, ScopeContext scope);

    /** The location, if the caller's scope sees it (OWN, FEDERATION_VIEW, a granted EXTERNAL). */
    Optional<LocationView> getLocation(UUID locationId, ScopeContext scope);

    /** The locations the caller's scope sees; a shop-scoped caller sees its own shop only. */
    LocationPage listLocations(LocationFilter filter, ScopeContext scope);

    /** The positions of a location by number, retired ones included; empty if the location is not visible. */
    List<TillPositionView> listTillPositions(UUID locationId, ScopeContext scope);
}
