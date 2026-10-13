package lk.coopfed.knoweb.kernel.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A device was assigned to a rollout ring (doc 31 section 4 and 5.2).
 */
public record DeviceRingAssigned(UUID eventId, UUID deviceId, int ring, Instant assignedAt) implements DomainEvent {

    public static final String TYPE = "device.ring_assigned.v1";
}
