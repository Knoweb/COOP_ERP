package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * The instruction to the sync gateway (19A K-08) to refuse this device from now on: its next
 * call is answered 403 with a signed revoke, and the till wipes its caches and locks, keeping
 * unacknowledged outbox rows for an administrator to recover (doc 32 section 9, "Suspension";
 * doc 21 section 3.4: "suspension publishes device.suspended.v1 which the sync gateway turns
 * into a signed revoke").
 *
 * <p>M1 publishes it whenever a device stops being allowed to sync: on suspension and on
 * retirement. It carries what the gateway needs to act without reading M1's tables. A
 * {@link DeviceReinstated} lifts it.
 *
 * @param cause SUSPENDED or RETIRED
 */
public record DeviceRevoked(
        UUID revokedDeviceId,
        UUID ownerEntityId,
        UUID locationId,
        String hardwareSerial,
        String cause,
        String reasonCode)
        implements DomainEvent {

    public static final String TYPE = "device.revoked.v1";

    public static final String CAUSE_SUSPENDED = "SUSPENDED";
    public static final String CAUSE_RETIRED = "RETIRED";
}
