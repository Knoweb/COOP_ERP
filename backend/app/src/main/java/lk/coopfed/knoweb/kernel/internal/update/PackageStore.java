package lk.coopfed.knoweb.kernel.internal.update;

import java.net.URI;
import java.time.Duration;

/**
 * Storage integration for holding and presigning release packages and artifacts (doc 31 section 4).
 */
public interface PackageStore {

    /**
     * Stores the raw bytes of a release package.
     */
    StoredPackage store(String platform, String version, byte[] packageBytes);

    /**
     * Retrieves the raw bytes of a release package.
     */
    byte[] retrieve(String packagePath);

    /**
     * Generates a pre-signed download URI for device update agents.
     */
    URI presignedDownloadUri(String packagePath, Duration validFor);

    record StoredPackage(String packagePath, String sha256Hex, long sizeBytes) {}
}
