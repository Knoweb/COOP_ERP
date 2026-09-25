package lk.coopfed.knoweb.m1party.api;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A till position exists at a shop, with its numbering series (21A section 6,
 * RegisterTillPosition; ADR-11: the series follow the position).
 */
public record TillPositionRegistered(
        UUID tillPositionId, UUID locationId, UUID ownerEntityId, int positionNo, List<UUID> seriesIds)
        implements DomainEvent {

    public static final String TYPE = "till_position.registered.v1";
}
