package lk.coopfed.knoweb.m2catalogue.internal.image;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lk.coopfed.knoweb.kernel.api.ObjectStorage;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The kernel's {@link ObjectStorage} with the objects in a map, for M2's tests: the kernel's own
 * implementation is proved against a store in {@code AttachmentsPostgresIntegrationTest} and
 * against MinIO in {@code S3ObjectStoreIntegrationTest}. The limits are fixed here (5 MB, the
 * register's default; JPEG and PNG and PDF) so a test can exceed them.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MemoryObjectStorage implements ObjectStorage {

    static final long MAX_BYTES = 5L * 1024 * 1024;

    public final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Bean
    @Primary
    ObjectStorage memoryObjectStorage() {
        return this;
    }

    @Override
    public PresignedPut presignPut(String objectKey, String contentType, Long contentLength, ScopeContext ctx) {
        if (!contentType.startsWith("image/") && !"application/pdf".equals(contentType)) {
            throw new ProblemException("attachment.content_type_not_allowed", Map.of("contentType", contentType));
        }
        if (contentLength != null && contentLength > MAX_BYTES) {
            throw new ProblemException(
                    "attachment.too_large", Map.of("contentLength", contentLength, "maxBytes", MAX_BYTES));
        }
        return new PresignedPut(
                URI.create("memory://put/" + objectKey + "?type=" + contentType),
                Clock.systemUTC().instant().plus(Duration.ofMinutes(15)),
                objectKey);
    }

    @Override
    public Verification verify(String objectKey, String expectedSha256Hex, ScopeContext ctx) {
        byte[] bytes = objects.get(objectKey);
        if (bytes == null) {
            return new Verification(Outcome.MISSING, null, null);
        }
        if (bytes.length > MAX_BYTES) {
            return new Verification(Outcome.TOO_LARGE, (long) bytes.length, null);
        }
        String hash = sha256(bytes);
        if (expectedSha256Hex != null && !expectedSha256Hex.equalsIgnoreCase(hash)) {
            return new Verification(Outcome.HASH_MISMATCH, (long) bytes.length, hash);
        }
        return new Verification(Outcome.VERIFIED, (long) bytes.length, hash);
    }

    @Override
    public byte[] read(String objectKey, ScopeContext ctx) {
        return objects.get(objectKey).clone();
    }

    @Override
    public void write(String objectKey, String contentType, byte[] bytes) {
        objects.put(objectKey, bytes.clone());
    }

    @Override
    public URI presignGet(String objectKey, String contentType) {
        return URI.create("memory://get/" + objectKey);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
