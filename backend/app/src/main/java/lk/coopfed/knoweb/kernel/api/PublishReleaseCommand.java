package lk.coopfed.knoweb.kernel.api;

import java.util.Map;

/**
 * Command to publish a new till application release to the release registry (doc 31 section 4).
 */
public record PublishReleaseCommand(
        String version,
        String platform,
        String packagePath,
        String packageHash,
        long packageSizeBytes,
        String minSupportedVersion,
        int targetRing,
        String notes,
        Map<String, Object> metadata) {}
