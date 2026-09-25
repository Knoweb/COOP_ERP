package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * An entity added a separation-of-duties pair, changed its mode or removed it (21A section 6,
 * SetSodPairMode). The two codes are in order ({@code permissionA < permissionB}), as stored.
 *
 * @param mode    INSTANCE or ROLE; the mode the pair had when it was removed, when
 *                {@code removed}
 * @param removed true when the entity's pair was deleted
 */
public record SodPairChanged(UUID sodPairId, String permissionA, String permissionB, String mode, boolean removed)
        implements DomainEvent {

    public static final String TYPE = "sod_pair.changed.v1";
}
