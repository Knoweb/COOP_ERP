package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A device was suspended: lost, stolen or faulty (doc 21 section 4.5). It keeps its position
 * until a replacement is assigned there. The instruction to the till itself is
 * {@link DeviceRevoked}, published beside this event.
 */
public record DeviceSuspended(
        UUID suspendedDeviceId, UUID ownerEntityId, UUID locationId, UUID tillPositionId, String reasonCode)
        implements DomainEvent {

    public static final String TYPE = "device.suspended.v1";
}
