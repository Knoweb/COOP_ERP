package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A suspended device was recovered and is ACTIVE again (doc 21 section 4.5). The sync gateway
 * lifts the revoke it answered the device with since {@link DeviceRevoked}.
 *
 * @param tillPositionId the position it returns to, or null when a replacement took it meanwhile
 */
public record DeviceReinstated(
        UUID reinstatedDeviceId, UUID ownerEntityId, UUID locationId, UUID tillPositionId, String reasonCode)
        implements DomainEvent {

    public static final String TYPE = "device.reinstated.v1";
}
