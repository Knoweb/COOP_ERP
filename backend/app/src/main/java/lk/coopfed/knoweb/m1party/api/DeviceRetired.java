package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A device was retired for good (doc 21 section 4.5); {@link DeviceRevoked} goes with it. */
public record DeviceRetired(UUID retiredDeviceId, UUID ownerEntityId, UUID locationId, String reasonCode)
        implements DomainEvent {

    public static final String TYPE = "device.retired.v1";
}
