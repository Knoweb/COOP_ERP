package lk.coopfed.knoweb.kernel.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Central update service governing release registration, rollout rings, pause controls, and
 * cryptographically signed per-device manifests (doc 31 section 4).
 */
public interface CentralUpdateService {

    /**
     * Publishes a new till release into the release registry.
     */
    ReleaseRecord publishRelease(PublishReleaseCommand cmd, ScopeContext scope);

    /**
     * Pauses rollout of a release, withholding it from target version resolution.
     */
    ReleaseRecord pauseRelease(String version, String platform, String reason, ScopeContext scope);

    /**
     * Resumes a paused release for rollout.
     */
    ReleaseRecord resumeRelease(String version, String platform, ScopeContext scope);

    /**
     * Assigns a device to a rollout ring (0: Lab, 1: Canary, 2: Wave, 3: Fleet).
     */
    void assignDeviceRing(UUID deviceId, UUID ownerEntityId, int ring, ScopeContext scope);

    /**
     * Resolves the target release for a device considering platform, rollout rings, and pause states.
     */
    Optional<ReleaseRecord> resolveTargetRelease(UUID deviceId, String currentVersion, String platform);

    /**
     * Generates a cryptographically signed manifest containing the resolved target version for a device.
     */
    DeviceUpdateManifest getDeviceUpdateManifest(UUID deviceId, String currentVersion, String platform);

    /**
     * Verifies the cryptographic signature of a manifest against central's release signing key.
     */
    boolean verifyManifest(DeviceUpdateManifest manifest);

    /**
     * Retrieves an individual release record by version and platform.
     */
    Optional<ReleaseRecord> getRelease(String version, String platform);

    /**
     * Lists releases for a platform.
     */
    List<ReleaseRecord> listReleases(String platform);
}
