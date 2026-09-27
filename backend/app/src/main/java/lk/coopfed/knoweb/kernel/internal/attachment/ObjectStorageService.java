package lk.coopfed.knoweb.kernel.internal.attachment;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.kernel.api.ObjectStorage;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.springframework.stereotype.Component;

/**
 * {@link ObjectStorage} on the same store and under the same register limits as the attachments
 * of a document (CR-19A-7). Stateless: the module that owns the object keeps its row.
 */
@Component
class ObjectStorageService implements ObjectStorage {

    /** objects/{module}/{entity uuid}/{object uuid}, optionally followed by a derived name (/thumb.png). */
    private static final Pattern KEY =
            Pattern.compile("objects/[a-z][a-z0-9]*/[0-9a-f-]{36}/[0-9a-f-]{36}(/[a-z0-9][a-z0-9._-]{0,63})?");

    private final AttachmentService attachments;
    private final ObjectStore store;
    private final SystemScope system;
    private final Clock clock;

    ObjectStorageService(AttachmentService attachments, ObjectStore store, SystemScope system, Clock clock) {
        this.attachments = attachments;
        this.store = store;
        this.system = system;
        this.clock = clock;
    }

    /**
     * The register's attachment.max_bytes as the caller's scope sees it. verify and read are
     * called outside a transaction (they talk to the store), so the value is read in one of its
     * own with the scope applied, as the attachment verifier does.
     */
    private long maxBytes(ScopeContext ctx) {
        return system.inScope(ctx, () -> attachments.maxBytes(ctx));
    }

    @Override
    public PresignedPut presignPut(String objectKey, String contentType, Long contentLength, ScopeContext ctx) {
        requireKey(objectKey);
        attachments.checkUpload(contentType, contentLength, ctx);
        Instant expiresAt = clock.instant().plus(attachments.presignFor());
        URI url = store.presignPut(objectKey, contentType, contentLength, attachments.presignFor());
        return new PresignedPut(url, expiresAt, objectKey);
    }

    @Override
    public Verification verify(String objectKey, String expectedSha256Hex, ScopeContext ctx) {
        requireKey(objectKey);
        Optional<Long> size = store.head(objectKey);
        if (size.isEmpty()) {
            return new Verification(Outcome.MISSING, null, null);
        }
        // Refused before it is read, as the attachment verifier does: hashing an object of any
        // size would starve the run.
        if (size.get() > maxBytes(ctx)) {
            return new Verification(Outcome.TOO_LARGE, size.get(), null);
        }
        String hash = store.sha256Hex(objectKey);
        if (expectedSha256Hex != null && !expectedSha256Hex.strip().equalsIgnoreCase(hash)) {
            return new Verification(Outcome.HASH_MISMATCH, size.get(), hash);
        }
        return new Verification(Outcome.VERIFIED, size.get(), hash);
    }

    @Override
    public byte[] read(String objectKey, ScopeContext ctx) {
        requireKey(objectKey);
        return store.read(objectKey, maxBytes(ctx));
    }

    @Override
    public void write(String objectKey, String contentType, byte[] bytes) {
        requireKey(objectKey);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Nothing to store under " + objectKey);
        }
        store.put(objectKey, contentType, bytes);
    }

    @Override
    public URI presignGet(String objectKey, String contentType) {
        requireKey(objectKey);
        return store.presignGet(objectKey, contentType, attachments.presignFor());
    }

    /** A module names only its own objects: a document's attachment key is not one. */
    private static void requireKey(String objectKey) {
        if (objectKey == null || !KEY.matcher(objectKey).matches()) {
            throw new IllegalArgumentException("Not a module object key: " + objectKey);
        }
    }
}
