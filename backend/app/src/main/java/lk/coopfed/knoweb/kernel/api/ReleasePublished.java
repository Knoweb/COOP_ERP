package lk.coopfed.knoweb.kernel.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A new application release was published to the release registry (doc 31 section 4).
 */
public record ReleasePublished(
        UUID eventId, UUID releaseId, String version, String platform, int targetRing, Instant publishedAt)
        implements DomainEvent {

    public static final String TYPE = "release.published.v1";
}
