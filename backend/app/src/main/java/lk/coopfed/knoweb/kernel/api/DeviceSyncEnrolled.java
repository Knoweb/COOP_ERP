package lk.coopfed.knoweb.kernel.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A device was given its sync credential and cursor (doc 32 section 8, device enrolment). M1 may
 * consume it to record on its device that the till is enrolled; the kernel writes nothing of M1.
 */
public record DeviceSyncEnrolled(
        UUID enrolledDeviceId,
        UUID ownerEntityId,
        UUID locationId,
        UUID tillPositionId,
        long nextDeviceSeq,
        Instant enrolledAt)
        implements DomainEvent {

    public static final String TYPE = "device.sync_enrolled.v1";
}
