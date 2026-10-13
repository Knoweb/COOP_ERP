package lk.coopfed.knoweb.kernel.internal.update;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import lk.coopfed.knoweb.kernel.internal.attachment.ObjectStore;
import org.springframework.stereotype.Component;

@Component
public class ObjectStoragePackageStore implements PackageStore {

    private final ObjectStore objectStore;

    public ObjectStoragePackageStore(ObjectStore objectStore) {
        this.objectStore = objectStore;
    }

    @Override
    public StoredPackage store(String platform, String version, byte[] packageBytes) {
        if (packageBytes == null || packageBytes.length == 0) {
            throw new IllegalArgumentException("packageBytes must not be empty");
        }
        String key = "releases/" + platform.toLowerCase() + "/" + version + "/package.bin";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(packageBytes);
            String sha256Hex = HexFormat.of().formatHex(digest);
            objectStore.put(key, "application/octet-stream", packageBytes);
            return new StoredPackage(key, sha256Hex, (long) packageBytes.length);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Override
    public byte[] retrieve(String packagePath) {
        return objectStore.read(packagePath, 100 * 1024 * 1024L);
    }

    @Override
    public URI presignedDownloadUri(String packagePath, Duration validFor) {
        return objectStore.presignGet(packagePath, "application/octet-stream", validFor);
    }
}
