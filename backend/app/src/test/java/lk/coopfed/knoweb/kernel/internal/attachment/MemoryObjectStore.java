package lk.coopfed.knoweb.kernel.internal.attachment;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Objects in a map, standing in for S3 behind the kernel's {@link ObjectStore}: the attachment
 * service, the verifier and {@code ObjectStorage} with its upload ledger run for real against
 * PostgreSQL, only the bytes live here. The S3 client itself is proved against MinIO in
 * {@code S3ObjectStoreIntegrationTest}. Public so that a module's tests (M2's images) go through
 * the kernel's own {@code ObjectStorage} rather than a copy of it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MemoryObjectStore implements ObjectStore {

    public final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Bean
    @Primary
    ObjectStore memoryObjectStore() {
        return this;
    }

    @Override
    public URI presignPut(String key, String contentType, Long contentLength, Duration validFor) {
        return URI.create("memory://put/" + key + "?type=" + contentType
                + (contentLength == null ? "" : "&length=" + contentLength));
    }

    @Override
    public URI presignGet(String key, String contentType, Duration validFor) {
        boolean image = contentType != null && contentType.startsWith("image/");
        return URI.create("memory://get/" + key + (image ? "" : "?disposition=attachment"));
    }

    @Override
    public Optional<Long> head(String key) {
        return Optional.ofNullable(objects.get(key)).map(bytes -> (long) bytes.length);
    }

    @Override
    public String sha256Hex(String key) {
        return sha256(objects.get(key));
    }

    @Override
    public byte[] read(String key, long maxBytes) {
        byte[] bytes = objects.get(key);
        if (bytes == null || bytes.length > maxBytes) {
            throw new IllegalStateException("No object " + key + " within " + maxBytes + " bytes");
        }
        return bytes.clone();
    }

    @Override
    public void put(String key, String contentType, byte[] bytes) {
        objects.put(key, bytes.clone());
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
