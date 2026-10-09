package lk.coopfed.knoweb.kernel.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A device heartbeat was received (doc 32 section 6). M1 consumes this to update its device registry.
 */
public record DeviceHeartbeatReported(UUID deviceId, String appVersion, Instant lastSeenAt) implements DomainEvent {

    public static final String TYPE = "device.heartbeat_reported.v1";
}
