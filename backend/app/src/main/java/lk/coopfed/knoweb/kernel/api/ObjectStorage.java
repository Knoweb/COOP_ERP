package lk.coopfed.knoweb.kernel.api;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * Objects a module owns outright, not through a document (CR-19A-7, accepted as revised): M2's
 * product images, whose owner is a SKU. {@link Attachments} reaches its owner only through
 * {@code document_id}; a SKU is no document, so the module keeps its business row (M2's
 * {@code catalogue.sku_image}) and the kernel keeps an upload ledger ({@code kernel.object_upload})
 * that holds every module to the rules K-09 gave a document's attachment:
 * <ul>
 *   <li>the register's {@code attachment.content_types} and {@code attachment.max_bytes} on what
 *       is uploaded and on what is derived;
 *   <li>an object is settled (VERIFIED or FAILED) only after its pre-signed PUT has expired, so the
 *       verified bytes cannot be replaced with the same URL ({@link Outcome#PENDING} until then);
 *   <li>a settled object never changes, and no new PUT URL is issued for it
 *       ({@code object.not_pending});
 *   <li>nothing is read, derived or served from an object that is not VERIFIED;
 *   <li>the entity in the key is the caller's: an OWN scope of that entity to upload, verify and
 *       derive, and the entity in scope to read ({@code object.scope_mismatch}).
 * </ul>
 *
 * <p>The key is {@code objects/{module}/{entity}/{objectId}}, built by {@link #keyOf} from a class
 * of the calling module; an object derived from it takes that key plus one segment
 * ({@code .../thumb.png}). Every method refuses a key of another module than the caller's: the
 * kernel looks at the calling class, so a module cannot upload, read or overwrite another
 * module's objects by writing the key by hand.
 *
 * <p>The kernel audits what it settles ({@code OBJECT_VERIFIED}, {@code OBJECT_FAILED}); the
 * module's command handlers audit their own rows as before.
 */
public interface ObjectStorage {

    /**
     * @param url       where the client PUTs the bytes, with the content type (and length, when
     *                  given) signed in
     * @param expiresAt after this the store refuses the URL; nothing is settled before it
     */
    record PresignedPut(URI url, Instant expiresAt, String objectKey) {}

    /** What the store holds under a key, against what the module announced. */
    enum Outcome {
        /** The pre-signed PUT is still valid: the bytes may still change, ask again after it expired. */
        PENDING,
        /** Nothing under the key (yet, unless {@link Verification#settled()}). */
        MISSING,
        /** Larger than the register's {@code attachment.max_bytes}; not read. */
        TOO_LARGE,
        /** There, but its SHA-256 is not the one announced. */
        HASH_MISMATCH,
        /** There, within the limit, with the announced hash (or any, when none was announced). */
        VERIFIED
    }

    /**
     * @param size      and {@code sha256Hex} are null when nothing was measured (PENDING, MISSING,
     *                  and the hash of TOO_LARGE)
     * @param settled   the ledger row is VERIFIED or FAILED and will never change: the answer is
     *                  final. False for PENDING, and for MISSING while the kernel's upload window
     *                  ({@code coop-erp.object-store.upload-window-hours}) is still open.
     */
    record Verification(Outcome outcome, Long size, String sha256Hex, boolean settled) {}

    /** The package every module lives under; the segment after it names the module. */
    String ROOT_PACKAGE = "lk.coopfed.knoweb.";

    /**
     * The key of an object a module owns: {@code objects/{module}/{entity}/{objectId}}, where the
     * module is the package of {@code anchor} ({@code lk.coopfed.knoweb.m2catalogue...} gives
     * {@code m2catalogue}). The anchor must be a class of the calling module: a module names its
     * own objects only.
     */
    static String keyOf(Class<?> anchor, UUID ownerEntityId, UUID objectId) {
        if (anchor == null || ownerEntityId == null || objectId == null) {
            throw new IllegalArgumentException("An object key needs an anchor class, an owner and an id");
        }
        String module = moduleOf(anchor);
        Class<?> caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .getCallerClass();
        if (!module.equals(moduleOf(caller))) {
            throw new IllegalArgumentException(
                    caller.getName() + " may not name an object of module " + module + ": use a class of its own");
        }
        return "objects/" + module + "/" + ownerEntityId + "/" + objectId;
    }

    /** The module a class belongs to: the first package segment below {@link #ROOT_PACKAGE}. */
    static String moduleOf(Class<?> type) {
        String name = type.getName();
        if (!name.startsWith(ROOT_PACKAGE)) {
            throw new IllegalArgumentException(name + " is not a class of a module");
        }
        String rest = name.substring(ROOT_PACKAGE.length());
        int dot = rest.indexOf('.');
        if (dot < 1) {
            throw new IllegalArgumentException(name + " is not a class of a module");
        }
        return rest.substring(0, dot);
    }

    /**
     * Authorises one upload of an object the caller's module owns and records it PENDING in the
     * ledger, in the caller's transaction (it is rolled back with the module's row). Asking again
     * for the same key while it is PENDING renews the window and the URL (a client that lost its
     * connection); once settled it is refused. The type and the size are held to the register's
     * {@code attachment.content_types} and {@code attachment.max_bytes}; a module narrows the
     * types further with its own item.
     *
     * @param contentLength the exact size, signed into the URL, or null when the client does not
     *                      know it (the verification still refuses an object over the limit)
     * @param sha256Hex     the hash the client announces, 64 hex digits, or null; the verification
     *                      compares the stored bytes with it
     * @param ctx           an OWN scope of the entity in the key
     * @throws ProblemException {@code object.scope_mismatch}, {@code object.not_pending},
     *                          {@code object.content_type_mismatch}, {@code attachment.content_type_invalid},
     *                          {@code attachment.content_type_not_allowed}, {@code attachment.too_large},
     *                          {@code attachment.hash_invalid}
     */
    PresignedPut presignPut(
            String objectKey, String contentType, Long contentLength, String sha256Hex, ScopeContext ctx);

    /**
     * Looks at what arrived and settles the ledger row: {@link Outcome#PENDING} while the PUT URL
     * is valid; then its size against {@code attachment.max_bytes}, its hash against the one
     * announced, and VERIFIED or FAILED, audited. A settled row answers what it was settled with.
     * Talks to the store and writes in transactions of its own: call it outside a transaction.
     *
     * @param ctx an OWN scope of the entity in the key
     * @throws ProblemException {@code object.scope_mismatch}, {@code object.not_found}
     */
    Verification verify(String objectKey, ScopeContext ctx);

    /**
     * The bytes of a verified object, or of an object derived from one, at most
     * {@code attachment.max_bytes} of them. Call it outside a transaction.
     *
     * @param ctx a scope in which the entity in the key is visible (OWN of it, the federation
     *            view, or an external grant naming it)
     * @throws ProblemException {@code object.scope_mismatch}, {@code object.not_found},
     *                          {@code object.not_verified}
     */
    byte[] read(String objectKey, ScopeContext ctx);

    /**
     * Stores what the module derived (a thumbnail) under a derived key, the verified object's key
     * plus one segment, replacing any earlier derivation. The verified original itself is never
     * written. Held to the register's type and size limits. Call it outside a transaction.
     *
     * @param ctx an OWN scope of the entity in the key
     * @throws ProblemException {@code object.not_derived}, {@code object.scope_mismatch},
     *                          {@code object.not_found}, {@code object.not_verified},
     *                          {@code attachment.content_type_not_allowed}, {@code attachment.too_large}
     */
    void write(String derivedKey, String contentType, byte[] bytes, ScopeContext ctx);

    /**
     * A pre-signed GET of a verified object or of one derived from it; anything but an image is
     * served as a download.
     *
     * <p>Who may see it is the module's business, not the ledger's: the SKU owner's image is shown
     * wherever the SKU is visible (a SHARED item, a federation lookup), which the ledger's
     * entity-scoped policies cannot know. So any scope with a class (not NONE) is admitted, and the
     * module passes only a key it has itself read under its own row-level security. The caller's
     * module must still be the key's.
     *
     * @throws ProblemException {@code object.scope_mismatch} (no scope), {@code object.not_found},
     *                          {@code object.not_verified}
     */
    URI presignGet(String objectKey, String contentType, ScopeContext ctx);
}
