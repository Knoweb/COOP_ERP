package lk.coopfed.knoweb.kernel.api;

import java.time.Instant;
import java.util.UUID;

/**
 * An application release rollout was paused (doc 31 section 4).
 */
public record ReleasePaused(
        UUID eventId, UUID releaseId, String version, String platform, String reason, Instant pausedAt)
        implements DomainEvent {

    public static final String TYPE = "release.paused.v1";
}
