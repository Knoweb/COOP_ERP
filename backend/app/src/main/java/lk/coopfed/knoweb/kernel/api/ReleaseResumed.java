package lk.coopfed.knoweb.kernel.api;

import java.time.Instant;
import java.util.UUID;

/**
 * An application release rollout was resumed (doc 31 section 4).
 */
public record ReleaseResumed(UUID eventId, UUID releaseId, String version, String platform, Instant resumedAt)
        implements DomainEvent {

    public static final String TYPE = "release.resumed.v1";
}
