package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A device was enrolled at a location (doc 21 section 5.3: device id, serial, position, reason).
 * The first component is the aggregate id the outbox keys the event by; it is not called
 * deviceId because the outbox reads a component of that name as the acting device.
 */
public record DeviceEnrolled(
        UUID enrolledDeviceId,
        UUID ownerEntityId,
        UUID locationId,
        String hardwareSerial,
        String deviceKind,
        String appVersion,
        String status)
        implements DomainEvent {

    public static final String TYPE = "device.enrolled.v1";
}
