package lk.coopfed.knoweb.kernel.api;

import java.time.Instant;
import java.util.UUID;

/**
 * An application release registered in the central release registry (doc 31 section 4).
 */
public record ReleaseRecord(
        UUID releaseId,
        UUID ownerEntityId,
        String version,
        String platform,
        String packagePath,
        String packageHash,
        long packageSizeBytes,
        String minSupportedVersion,
        int targetRing,
        String status,
        Instant publishedAt,
        String publishedBy,
        Instant pausedAt,
        String pausedBy,
        String notes) {}
