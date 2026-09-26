package lk.coopfed.knoweb.kernel.api;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Objects a module owns outright, not through a document (CR-19A-7): M2's product images, whose
 * owner is a SKU. {@link Attachments} reaches its owner only through {@code document_id}; a SKU
 * is no document, so the module keeps the row (and its PENDING, settled and immutable states) in
 * its own table and asks the kernel for the rest: the pre-signed PUT under the register's limits,
 * the verification of what arrived, a bounded read and a write for what it derives (a thumbnail),
 * and a pre-signed GET. 22A section 2 names this pair: "PresignService for image upload; object
 * storage client".
 *
 * <p>The key names its module and owner: {@code objects/{module}/{entity}/{objectId}}, built by
 * {@link #keyOf}; an object the module derives from it takes that key plus one more segment
 * ({@code .../thumb.png}). Asking again for the same key renews the URL (the presign is idempotent per
 * object id); the module decides whether the row it keeps still takes an upload. The rules of
 * K-09 carry over and are the module's to keep: settle a row only after its URL has expired
 * ({@link PresignedPut#expiresAt()}), so the verified bytes cannot be replaced, and never change
 * a settled row.
 *
 * <p>Nothing here writes to the database or audits: the module's command handler does both.
 */
public interface ObjectStorage {

    /**
     * @param url       where the client PUTs the bytes, with the content type (and length, when
     *                  given) signed in
     * @param expiresAt after this the store refuses the URL; settle nothing before it
     */
    record PresignedPut(URI url, Instant expiresAt, String objectKey) {}

    /** What the store holds under a key, against what the module expected. */
    enum Outcome {
        /** Nothing under the key (yet). */
        MISSING,
        /** Larger than the register's {@code attachment.max_bytes}; not read. */
        TOO_LARGE,
        /** There, but its SHA-256 is not the one announced. */
        HASH_MISMATCH,
        /** There, within the limit, with the announced hash (or any, when none was announced). */
        VERIFIED
    }

    /** @param size and {@code sha256Hex} are null when the outcome is MISSING or TOO_LARGE (hash) */
    record Verification(Outcome outcome, Long size, String sha256Hex) {}

    /** {@code module} is the module's package name ({@code m2catalogue}), lower case. */
    Pattern MODULE = Pattern.compile("[a-z][a-z0-9]*");

    /** The key of an object a module owns: {@code objects/{module}/{entity}/{objectId}}. */
    static String keyOf(String module, UUID ownerEntityId, UUID objectId) {
        if (module == null || !MODULE.matcher(module).matches() || ownerEntityId == null || objectId == null) {
            throw new IllegalArgumentException("An object key needs a module name, an owner and an id");
        }
        return "objects/" + module + "/" + ownerEntityId + "/" + objectId;
    }

    /**
     * Authorises one upload of an object the caller's module owns. The type and the size are held
     * to the register's {@code attachment.content_types} and {@code attachment.max_bytes}, as for
     * a document's attachment; a module narrows the types further with its own item.
     *
     * @param contentLength the exact size, signed into the URL, or null when the client does not
     *                      know it (the verification still refuses an object over the limit)
     * @throws ProblemException {@code attachment.content_type_invalid},
     *                          {@code attachment.content_type_not_allowed}, {@code attachment.too_large}
     */
    PresignedPut presignPut(String objectKey, String contentType, Long contentLength, ScopeContext ctx);

    /**
     * Looks at what arrived: its size against {@code attachment.max_bytes} (in the scope given),
     * then its hash against {@code expectedSha256Hex} when one is given. Talks to the store; call
     * it outside a database transaction.
     */
    Verification verify(String objectKey, String expectedSha256Hex, ScopeContext ctx);

    /** The bytes of a verified object, at most {@code attachment.max_bytes} of them. */
    byte[] read(String objectKey, ScopeContext ctx);

    /** Stores what the module derived (a thumbnail) under a key of its own, replacing any. */
    void write(String objectKey, String contentType, byte[] bytes);

    /** A pre-signed GET; anything but an image is served as a download. */
    URI presignGet(String objectKey, String contentType);
}
