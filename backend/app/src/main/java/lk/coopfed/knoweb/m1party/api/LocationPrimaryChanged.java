package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * The shop's primary till changed (21A section 6, SetPrimaryTill). When a device holds the new
 * position, {@code holderDeviceId} names it and the kernel has published
 * {@code series.holder_changed.v1} for every location series in the same transaction; null
 * means no device is assigned yet and the counters stay where they were.
 */
public record LocationPrimaryChanged(
        UUID changedLocationId,
        UUID ownerEntityId,
        UUID previousTillPositionId,
        UUID primaryTillPositionId,
        UUID holderDeviceId)
        implements DomainEvent {

    public static final String TYPE = "location.primary_changed.v1";
}
