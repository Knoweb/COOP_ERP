package lk.coopfed.knoweb.kernel.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Cryptographically signed manifest containing the resolved target release for a device (doc 31 section 4 and 5.4).
 */
public record DeviceUpdateManifest(
        UUID deviceId,
        String targetVersion,
        String platform,
        String packageUrl,
        String packageHash,
        long packageSizeBytes,
        String minSupportedVersion,
        int targetRing,
        Instant issuedAt,
        String signature,
        String keyId,
        boolean updateAvailable) {

    /** Canonical line-delimited format used for cryptographic signature generation and verification. */
    public String canonicalText() {
        return String.format(
                "device:%s\nversion:%s\nplatform:%s\nhash:%s\nsize:%d\nfloor:%s\nring:%d\nissued:%s",
                deviceId,
                targetVersion,
                platform,
                packageHash != null ? packageHash : "",
                packageSizeBytes,
                minSupportedVersion != null ? minSupportedVersion : "0.0.0",
                targetRing,
                issuedAt);
    }
}
