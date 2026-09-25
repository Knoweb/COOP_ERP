package lk.coopfed.knoweb.m1party.api;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A till position retired for good; its series are closed (21A section 6, RetireTillPosition). */
public record TillPositionRetired(
        UUID tillPositionId, UUID locationId, UUID ownerEntityId, int positionNo, List<UUID> closedSeriesIds)
        implements DomainEvent {

    public static final String TYPE = "till_position.retired.v1";
}
