package lk.coopfed.knoweb.kernel.internal.attachment;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.ObjectStorage;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link ObjectStorage} on the same store and under the same register limits as the attachments
 * of a document, with the upload ledger {@code kernel.object_upload} (kernel V0060; CR-19A-7 as
 * revised on 27 September 2026). The module keeps its business row; the ledger keeps the K-09
 * rules for every module at once: a settle only after the presign window, a settled row never
 * changed, no new PUT URL once settled, nothing read, derived or served from an unverified
 * object, the entity in the key the caller's.
 *
 * <p>presignPut writes in the caller's transaction, so the ledger row is rolled back with the
 * module's row. The other methods talk to the store and read or settle the ledger in transactions
 * of their own ({@link SystemScope#inOwnTransaction}), never on the caller's connection.
 */
@Component
class ObjectStorageService implements ObjectStorage {

    static final String AUDIT_VERIFIED = "OBJECT_VERIFIED";
    static final String AUDIT_FAILED = "OBJECT_FAILED";

    private static final String UUID_TEXT = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    /** objects/{module}/{entity uuid}/{object uuid}, optionally followed by a derived name (/thumb.png). */
    private static final Pattern KEY = Pattern.compile(
            "(objects/([a-z][a-z0-9]*)/(" + UUID_TEXT + ")/(" + UUID_TEXT + "))(/[a-z0-9][a-z0-9._-]{0,63})?");

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private final AttachmentService attachments;
    private final ObjectStore store;
    private final SystemScope system;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final Clock clock;
    private final Duration uploadWindow;

    ObjectStorageService(
            AttachmentService attachments,
            ObjectStore store,
            SystemScope system,
            JdbcTemplate jdbc,
            AuditFacade audit,
            Clock clock,
            @Value("${coop-erp.object-store.upload-window-hours}") int uploadWindowHours) {
        this.attachments = attachments;
        this.store = store;
        this.system = system;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.uploadWindow = Duration.ofHours(uploadWindowHours);
    }

    // ---- the four questions of every call -------------------------------------------------------

    /** A parsed key: the base object's key, its module, owner and id, and the derived name if any. */
    record Key(String text, String base, String module, UUID entityId, UUID objectId, boolean derived) {}

    /**
     * The key's shape, and the caller's module against the key's. A malformed key or another
     * module's key is a programming error, not a request to answer: IllegalArgumentException.
     */
    static Key parse(String objectKey) {
        Matcher m = objectKey == null ? null : KEY.matcher(objectKey);
        if (m == null || !m.matches()) {
            throw new IllegalArgumentException("Not a module object key: " + objectKey);
        }
        Key key = new Key(
                objectKey,
                m.group(1),
                m.group(2),
                UUID.fromString(m.group(3)),
                UUID.fromString(m.group(4)),
                m.group(5) != null);
        String caller = callerModule();
        if (!key.module().equals(caller)) {
            throw new IllegalArgumentException(
                    "Module " + caller + " may not use an object of module " + key.module() + ": " + objectKey);
        }
        return key;
    }

    /**
     * The module of the first class up the stack that is not this service: the module that
     * called {@link ObjectStorage}. A proxy class ({@code $$}) is skipped; the method it wraps is
     * the caller's own frame just above it.
     */
    private static String callerModule() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(type -> type != ObjectStorageService.class
                                && type.getName().startsWith(ROOT_PACKAGE)
                                && !type.getName().contains("$$"))
                        .findFirst()
                        .map(ObjectStorage::moduleOf)
                        .orElse("?"));
    }

    /** Uploading, verifying and deriving are the owner's: an OWN scope of the key's entity. */
    private static void requireOwner(Key key, ScopeContext ctx) {
        if (ctx == null
                || ctx.policyClass() != PolicyClass.OWN
                || !key.entityId().equals(ctx.entityId())) {
            throw new ProblemException("object.scope_mismatch", Map.of("objectKey", key.text()));
        }
    }

    /** Reading: the key's entity in scope, as the ledger's read policies would admit it. */
    private static void requireInScope(Key key, ScopeContext ctx) {
        boolean inScope = ctx != null
                && switch (ctx.policyClass()) {
                    case OWN, PARTY -> key.entityId().equals(ctx.entityId());
                    case FEDERATION_VIEW -> true;
                    case EXTERNAL_TIMEBOXED -> ctx.grantedEntities().contains(key.entityId());
                    default -> false;
                };
        if (!inScope) {
            throw new ProblemException("object.scope_mismatch", Map.of("objectKey", key.text()));
        }
    }

    // ---- the ledger -----------------------------------------------------------------------------

    /** One row of kernel.object_upload. */
    record Row(
            String objectKey,
            String contentType,
            Long contentLength,
            String contentHash,
            String status,
            String failure,
            Instant uploadExpiresAt,
            Instant createdAt) {

        boolean settled() {
            return !"PENDING".equals(status);
        }

        /** What a settled row answers, every time it is asked again. */
        Verification replay() {
            if ("VERIFIED".equals(status)) {
                return new Verification(Outcome.VERIFIED, contentLength, contentHash, true);
            }
            return new Verification(Outcome.valueOf(failure), contentLength, null, true);
        }
    }

    /** The ledger row of a base key, under whatever scope the current transaction carries. */
    private Optional<Row> find(String baseKey) {
        return jdbc
                .query(
                        """
                        select object_key, content_type, content_length, content_hash, status, failure,
                               upload_expires_at, created_at
                          from kernel.object_upload where object_key = ?
                        """,
                        (rs, rowNum) -> new Row(
                                rs.getString("object_key"),
                                rs.getString("content_type"),
                                rs.getObject("content_length", Long.class),
                                rs.getString("content_hash"),
                                rs.getString("status"),
                                rs.getString("failure"),
                                rs.getTimestamp("upload_expires_at").toInstant(),
                                rs.getTimestamp("created_at").toInstant()),
                        baseKey)
                .stream()
                .findFirst();
    }

    /** The base row, VERIFIED, in the scope given; else not found or not verified. */
    private Row requireVerified(Key key, ScopeContext scope) {
        Row row = system.inOwnTransaction(scope, () -> find(key.base()))
                .orElseThrow(() -> new ProblemException("object.not_found", Map.of("objectKey", key.base())));
        if (!"VERIFIED".equals(row.status())) {
            throw new ProblemException("object.not_verified", Map.of("objectKey", key.base(), "status", row.status()));
        }
        return row;
    }

    // ---- ObjectStorage --------------------------------------------------------------------------

    @Override
    public PresignedPut presignPut(
            String objectKey, String contentType, Long contentLength, String sha256Hex, ScopeContext ctx) {
        Key key = parse(objectKey);
        if (key.derived()) {
            throw new IllegalArgumentException(
                    "An upload is presigned for an object's own key, not a derived one: " + objectKey);
        }
        requireOwner(key, ctx);
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("presignPut records the upload inside the handler's transaction");
        }

        attachments.checkUpload(contentType, contentLength, ctx);
        String hash = sha256Hex == null ? null : sha256Hex.strip().toLowerCase();
        if (hash != null && !SHA256_HEX.matcher(hash).matches()) {
            throw new ProblemException("attachment.hash_invalid");
        }

        Instant now = clock.instant();
        Instant expiresAt = now.plus(attachments.uploadUrlFor(ctx));

        // Asking again for the same key renews the window of a PENDING row (a client that lost its
        // connection); a settled row gets no new URL, so its verified bytes cannot be replaced.
        Optional<Row> existing = find(key.base());
        if (existing.isPresent()) {
            Row row = existing.get();
            if (row.settled()) {
                throw new ProblemException(
                        "object.not_pending", Map.of("objectKey", objectKey, "status", row.status()));
            }
            if (!row.contentType().equals(contentType)) {
                throw new ProblemException(
                        "object.content_type_mismatch",
                        Map.of("objectKey", objectKey, "contentType", contentType, "recorded", row.contentType()));
            }
            jdbc.update(
                    "update kernel.object_upload set upload_expires_at = ?, content_hash = coalesce(?, content_hash),"
                            + " content_length = ? where object_key = ? and status = 'PENDING'",
                    Timestamp.from(expiresAt),
                    hash,
                    contentLength,
                    key.base());
        } else {
            jdbc.update(
                    """
                    insert into kernel.object_upload (
                        object_key, owner_module, owner_entity_id, content_type, content_length, content_hash,
                        status, upload_expires_at, created_at
                    ) values (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                    """,
                    key.base(),
                    key.module(),
                    key.entityId(),
                    contentType,
                    contentLength,
                    hash,
                    Timestamp.from(expiresAt),
                    Timestamp.from(now));
        }

        URI url = store.presignPut(key.base(), contentType, contentLength, Duration.between(now, expiresAt));
        return new PresignedPut(url, expiresAt, key.base());
    }

    @Override
    public Verification verify(String objectKey, ScopeContext ctx) {
        Key key = parse(objectKey);
        if (key.derived()) {
            throw new IllegalArgumentException("A derived object is not verified, its original is: " + objectKey);
        }
        requireOwner(key, ctx);

        Row row = system.inOwnTransaction(ctx, () -> find(key.base()))
                .orElseThrow(() -> new ProblemException("object.not_found", Map.of("objectKey", objectKey)));
        if (row.settled()) {
            return row.replay();
        }
        Instant now = clock.instant();
        // Until the PUT URL expires the same URL could replace the bytes after they were hashed.
        if (now.isBefore(row.uploadExpiresAt())) {
            return new Verification(Outcome.PENDING, null, null, false);
        }

        Optional<Long> size = store.head(key.base());
        if (size.isEmpty()) {
            if (row.createdAt().plus(uploadWindow).isAfter(now)) {
                return new Verification(Outcome.MISSING, null, null, false);
            }
            return settle(key, row, Outcome.MISSING, null, null, "not uploaded within " + uploadWindow, ctx);
        }

        long maxBytes = system.inOwnTransaction(ctx, () -> attachments.maxBytes(ctx));
        // Refused before it is read, as the attachment verifier does: hashing an object of any
        // size would starve the run.
        if (size.get() > maxBytes) {
            return settle(
                    key,
                    row,
                    Outcome.TOO_LARGE,
                    size.get(),
                    null,
                    "too large: " + size.get() + " bytes, at most " + maxBytes,
                    ctx);
        }

        String hash = store.sha256Hex(key.base());
        if (row.contentHash() != null && !row.contentHash().equals(hash)) {
            return settle(
                    key,
                    row,
                    Outcome.HASH_MISMATCH,
                    size.get(),
                    hash,
                    "hash mismatch: announced " + row.contentHash() + ", stored " + hash,
                    ctx);
        }
        return settle(key, row, Outcome.VERIFIED, size.get(), hash, null, ctx);
    }

    /**
     * VERIFIED or FAILED, audited, in a transaction of its own in the owner's scope, and only if
     * the row is still PENDING: a run that finds it settled by another answers what that one
     * settled, with nothing audited twice.
     */
    private Verification settle(
            Key key, Row row, Outcome outcome, Long size, String hash, String why, ScopeContext ctx) {
        boolean verified = outcome == Outcome.VERIFIED;
        Boolean settledHere = system.inOwnTransaction(ctx, () -> {
            int changed = jdbc.update(
                    "update kernel.object_upload set status = ?, failure = ?, content_hash = ?,"
                            + " content_length = coalesce(?, content_length), settled_at = ?"
                            + " where object_key = ? and status = 'PENDING'",
                    verified ? "VERIFIED" : "FAILED",
                    verified ? null : outcome.name(),
                    verified ? hash : row.contentHash(),
                    size,
                    Timestamp.from(clock.instant()),
                    key.base());
            if (changed == 0) {
                return false;
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", verified ? "VERIFIED" : "FAILED");
            after.put("objectKey", key.base());
            after.put("module", key.module());
            if (size != null) {
                after.put("size", size);
            }
            if (hash != null) {
                after.put("contentHash", hash);
            }
            if (verified) {
                audit.record(
                        AUDIT_VERIFIED, Subject.of("object", key.objectId()), Map.of("status", "PENDING"), after, ctx);
            } else {
                after.put("failure", outcome.name());
                audit.record(
                        AUDIT_FAILED,
                        Subject.of("object", key.objectId()),
                        Map.of("status", "PENDING"),
                        after,
                        ctx,
                        why);
            }
            return true;
        });
        if (Boolean.TRUE.equals(settledHere)) {
            return new Verification(outcome, size, hash, true);
        }
        return system.inOwnTransaction(ctx, () -> find(key.base()))
                .map(Row::replay)
                .orElseThrow(() -> new ProblemException("object.not_found", Map.of("objectKey", key.base())));
    }

    // ---- what the clean-up job does (kernel V0063) --------------------------------------------

    static final String AUDIT_DELETED = "OBJECT_DELETED";

    /** A FAILED module object still in the store. */
    record Failed(String objectKey, String module, UUID ownerEntityId) {}

    /**
     * FAILED ledger rows settled before {@code settledBefore} whose object has not been deleted,
     * the oldest first, at most {@code limit}, under the caller's scope (the job reads as a
     * federation-wide viewer). A VERIFIED object is never offered: it is the module's to keep.
     */
    List<Failed> failedWithObject(Instant settledBefore, int limit) {
        return jdbc.query(
                """
                select object_key, owner_module, owner_entity_id from kernel.object_upload
                 where status = 'FAILED' and object_deleted_at is null and settled_at < ?
                 order by settled_at
                 limit ?
                """,
                (rs, rowNum) -> new Failed(
                        rs.getString("object_key"),
                        rs.getString("owner_module"),
                        rs.getObject("owner_entity_id", UUID.class)),
                Timestamp.from(settledBefore),
                limit);
    }

    /** The object of a FAILED ledger row is gone from the store: the row says when, once, audited. */
    boolean objectDeleted(Failed row, ScopeContext ctx) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The ledger is written inside a transaction");
        }
        int changed = jdbc.update(
                "update kernel.object_upload set object_deleted_at = ?"
                        + " where object_key = ? and status = 'FAILED' and object_deleted_at is null",
                Timestamp.from(clock.instant()),
                row.objectKey());
        if (changed == 0) {
            return false;
        }
        // A ledger row holds a base key, objects/{module}/{entity}/{object}: the object id is last.
        UUID objectId =
                UUID.fromString(row.objectKey().substring(row.objectKey().lastIndexOf('/') + 1));
        audit.record(
                AUDIT_DELETED,
                Subject.of("object", objectId),
                Map.of("status", "FAILED"),
                Map.of("objectKey", row.objectKey(), "module", row.module()),
                ctx,
                "Failed upload kept past its retention");
        return true;
    }

    @Override
    public byte[] read(String objectKey, ScopeContext ctx) {
        Key key = parse(objectKey);
        requireInScope(key, ctx);
        requireVerified(key, ctx);
        long maxBytes = system.inOwnTransaction(ctx, () -> attachments.maxBytes(ctx));
        return store.read(key.text(), maxBytes);
    }

    @Override
    public void write(String derivedKey, String contentType, byte[] bytes, ScopeContext ctx) {
        Key key = parse(derivedKey);
        // The verified original is never written: it is what the hash describes.
        if (!key.derived()) {
            throw new ProblemException("object.not_derived", Map.of("objectKey", derivedKey));
        }
        requireOwner(key, ctx);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Nothing to store under " + derivedKey);
        }
        requireVerified(key, ctx);
        system.inOwnTransaction(ctx, () -> {
            attachments.checkUpload(contentType, (long) bytes.length, ctx);
            return null;
        });
        store.put(key.text(), contentType, bytes);
    }

    @Override
    public URI presignGet(String objectKey, String contentType, ScopeContext ctx) {
        Key key = parse(objectKey);
        // Who may see the object is the module's decision, made when it read the key under its
        // own row-level security (a SHARED item's image is seen wherever the item is); the
        // ledger's entity policies cannot know it. A request with no scope is still refused.
        if (ctx == null || !ctx.hasActiveScope() || ctx.policyClass() == PolicyClass.NONE) {
            throw new ProblemException("object.scope_mismatch", Map.of("objectKey", objectKey));
        }
        // Whether it is verified is the ledger's, read with the federation-wide view for that
        // question alone.
        requireVerified(key, SystemScope.federationView());
        return store.presignGet(key.text(), contentType, attachments.readUrlFor());
    }
}
