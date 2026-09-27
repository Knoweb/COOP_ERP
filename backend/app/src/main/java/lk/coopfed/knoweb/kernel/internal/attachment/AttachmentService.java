package lk.coopfed.knoweb.kernel.internal.attachment;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lk.coopfed.knoweb.kernel.api.AttachmentCompleted;
import lk.coopfed.knoweb.kernel.api.Attachments;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The attachment service of 19A section 9 on {@code kernel.document_attachment} (K-07) and an
 * {@link ObjectStore}. Runs in the caller's transaction and scope: the row is written under
 * the document's policies, so a document the caller does not own takes no attachment and a
 * document the caller cannot read gives no URL.
 *
 * <p>The limits are the register's: {@code attachment.max_bytes} (doc 32 section 4 sizes an
 * image at about 300 KB) and {@code attachment.content_types}, the types that may be attached.
 */
@Component
class AttachmentService implements Attachments {

    static final String AUDIT_PRESIGNED = "ATTACHMENT_PRESIGNED";
    static final String AUDIT_COMPLETED = "ATTACHMENT_COMPLETED";
    static final String AUDIT_FAILED = "ATTACHMENT_FAILED";

    static final String MAX_BYTES = "attachment.max_bytes";
    static final String CONTENT_TYPES = "attachment.content_types";
    static final String VERIFY_BATCH_SIZE = "attachment.verify.batch_size";
    static final String UPLOAD_URL_MINUTES = "attachment.upload_url_minutes";
    static final String FAILED_RETENTION_DAYS = "attachment.failed_retention_days";
    static final String CLEANUP_BATCH_SIZE = "attachment.cleanup.batch_size";
    static final String AUDIT_OBJECT_DELETED = "ATTACHMENT_OBJECT_DELETED";

    private static final Pattern CONTENT_TYPE = Pattern.compile("[a-z]+/[a-z0-9.+-]+");
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private final JdbcTemplate jdbc;
    private final ObjectStore store;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final ConfigRegistry config;
    private final Clock clock;
    private final Duration readUrlFor;

    AttachmentService(
            JdbcTemplate jdbc,
            ObjectStore store,
            AuditFacade audit,
            EventPublisher events,
            ConfigRegistry config,
            Clock clock,
            @Value("${coop-erp.object-store.presign-minutes}") int readUrlMinutes) {
        this.jdbc = jdbc;
        this.store = store;
        this.audit = audit;
        this.events = events;
        this.config = config;
        this.clock = clock;
        this.readUrlFor = Duration.ofMinutes(readUrlMinutes);
    }

    @Override
    public PresignedUpload presignUpload(
            UUID documentId,
            UUID attachmentId,
            String contentType,
            Long contentLength,
            String sha256Hex,
            ScopeContext ctx) {
        requireTransaction();

        checkUpload(contentType, contentLength, ctx);

        String hash = sha256Hex == null ? null : sha256Hex.strip().toLowerCase();

        if (hash != null && !SHA256_HEX.matcher(hash).matches()) {
            throw new ProblemException("attachment.hash_invalid");
        }

        // The document must be the caller's: a document the caller cannot see is "not found",
        // and one it can see but does not own (a counterparty) is said so, before the row's
        // policy would refuse the insert with a bare database error.
        UUID ownerEntityId = jdbc
                .query(
                        "select owner_entity_id from kernel.document where document_id = ?",
                        (rs, rowNum) -> rs.getObject("owner_entity_id", UUID.class),
                        documentId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ProblemException("attachment.document_not_found"));
        Boolean owned = jdbc.queryForObject("select kernel.document_owned(?)", Boolean.class, documentId);
        if (!Boolean.TRUE.equals(owned)) {
            throw new ProblemException("attachment.document_not_owned");
        }

        UUID id = attachmentId == null ? Ids.next() : attachmentId;
        String key = keyOf(ownerEntityId, documentId, id);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(uploadUrlFor(ctx));

        // Asking again for the same attachment (a till that lost its connection and comes back,
        // doc 32 section 4: the upload is resumable and the URL lasts fifteen minutes) renews the
        // window of the PENDING row; a settled row or another document's row is refused.
        Optional<Existing> existing = existing(id);
        if (existing.isPresent()) {
            if (!existing.get().documentId().equals(documentId)) {
                throw new ProblemException("attachment.document_mismatch");
            }
            if (!"PENDING".equals(existing.get().status())) {
                throw new ProblemException(
                        "attachment.not_pending",
                        Map.of("status", existing.get().status()));
            }
            jdbc.update(
                    "update kernel.document_attachment set upload_expires_at = ?, content_hash = coalesce(?, content_hash)"
                            + " where attachment_id = ? and status = 'PENDING'",
                    Timestamp.from(expiresAt),
                    hash,
                    id);
        } else {
            jdbc.update(
                    """
                    insert into kernel.document_attachment (
                        attachment_id, document_id, object_key, content_hash, content_type, status,
                        captured_at, captured_by, device_id, upload_expires_at
                    ) values (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
                    """,
                    id,
                    documentId,
                    key,
                    hash,
                    contentType,
                    Timestamp.from(now),
                    ctx == null ? null : ctx.userId(),
                    ctx == null ? null : ctx.deviceId(),
                    Timestamp.from(expiresAt));
        }

        URI url = store.presignPut(key, contentType, contentLength, Duration.between(now, expiresAt));

        audit.record(
                AUDIT_PRESIGNED,
                Subject.of("document", documentId),
                null,
                Map.of("attachmentId", id.toString(), "contentType", contentType, "objectKey", key),
                ctx);

        return new PresignedUpload(id, url, expiresAt, key);
    }

    @Override
    public Optional<URI> presignDownload(UUID attachmentId, ScopeContext ctx) {
        return jdbc
                .query(
                        "select object_key, content_type from kernel.document_attachment"
                                + " where attachment_id = ? and status = 'COMPLETE'",
                        (rs, rowNum) ->
                                store.presignGet(rs.getString("object_key"), rs.getString("content_type"), readUrlFor),
                        attachmentId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<String> status(UUID attachmentId) {
        return jdbc
                .query(
                        "select status from kernel.document_attachment where attachment_id = ?",
                        (rs, rowNum) -> rs.getString("status"),
                        attachmentId)
                .stream()
                .findFirst();
    }

    private record Existing(UUID documentId, String status) {}

    private Optional<Existing> existing(UUID attachmentId) {
        return jdbc
                .query(
                        "select document_id, status from kernel.document_attachment where attachment_id = ?",
                        (rs, rowNum) -> new Existing(rs.getObject("document_id", UUID.class), rs.getString("status")),
                        attachmentId)
                .stream()
                .findFirst();
    }

    /** The register's limits on what may be uploaded, for a document's attachment and a module's object alike. */
    void checkUpload(String contentType, Long contentLength, ScopeContext ctx) {
        if (contentType == null || !CONTENT_TYPE.matcher(contentType).matches()) {
            throw new ProblemException(
                    "attachment.content_type_invalid", Map.of("contentType", String.valueOf(contentType)));
        }
        if (!allowedContentTypes(ctx).contains(contentType)) {
            throw new ProblemException("attachment.content_type_not_allowed", Map.of("contentType", contentType));
        }

        long maxBytes = maxBytes(ctx);
        if (contentLength != null && (contentLength < 1 || contentLength > maxBytes)) {
            throw new ProblemException(
                    "attachment.too_large", Map.of("contentLength", contentLength, "maxBytes", maxBytes));
        }
    }

    /**
     * How long an upload URL is valid: the register's {@code attachment.upload_url_minutes}
     * (decided 27 September 2026, CR-19A-8). The verifier settles a row only after its URL has
     * expired, so this, plus the verifier's interval, is how long a decision waiting on the
     * evidence waits after the upload; shortening it is how the wait is shortened.
     */
    Duration uploadUrlFor(ScopeContext ctx) {
        return Duration.ofMinutes(config.getInt(UPLOAD_URL_MINUTES, ctx, 15));
    }

    /** How long a read URL is valid ({@code coop-erp.object-store.presign-minutes}). */
    Duration readUrlFor() {
        return readUrlFor;
    }

    private Set<String> allowedContentTypes(ScopeContext ctx) {
        String list = config.getOrDefault(CONTENT_TYPES, ctx, "image/jpeg,image/png,image/webp,application/pdf");
        return Arrays.stream(list.split(",")).map(String::strip).collect(Collectors.toSet());
    }

    long maxBytes(ScopeContext ctx) {
        return config.getInt(MAX_BYTES, ctx, 5 * 1024 * 1024);
    }

    // ---- what the verifier does, in the OWN scope of the document's entity ---------------------

    /** A PENDING row, with what the verifier needs to know about it. */
    record Pending(
            UUID attachmentId,
            UUID documentId,
            UUID ownerEntityId,
            String objectKey,
            String expectedHash,
            Instant capturedAt,
            Instant uploadExpiresAt) {}

    /**
     * The PENDING attachments whose upload window has ended, the oldest first, at most
     * {@code limit} of them, under the caller's scope (the verifier reads as a federation-wide
     * viewer). A row whose pre-signed PUT is still valid is not offered: the bytes could still
     * change after the hash was checked.
     */
    List<Pending> pending(Instant now, int limit) {
        return jdbc.query(
                """
                select a.attachment_id, a.document_id, d.owner_entity_id, a.object_key, a.content_hash,
                       a.captured_at, a.upload_expires_at
                  from kernel.document_attachment a
                  join kernel.document d on d.document_id = a.document_id
                 where a.status = 'PENDING' and (a.upload_expires_at is null or a.upload_expires_at <= ?)
                 order by a.captured_at
                 limit ?
                """,
                (rs, rowNum) -> new Pending(
                        rs.getObject("attachment_id", UUID.class),
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getString("object_key"),
                        rs.getString("content_hash"),
                        rs.getTimestamp("captured_at").toInstant(),
                        rs.getTimestamp("upload_expires_at") == null
                                ? null
                                : rs.getTimestamp("upload_expires_at").toInstant()),
                Timestamp.from(now),
                limit);
    }

    /**
     * The object is there and its hash is right: COMPLETE, audited, published. A row that is no
     * longer PENDING (another run settled it) is left as it is, with nothing audited or published.
     */
    boolean complete(Pending row, String hash, ScopeContext ctx) {
        requireTransaction();
        int settled = jdbc.update(
                "update kernel.document_attachment set status = 'COMPLETE', content_hash = ?, settled_at = ?"
                        + " where attachment_id = ? and status = 'PENDING'",
                hash,
                Timestamp.from(clock.instant()),
                row.attachmentId());
        if (settled == 0) {
            return false;
        }
        audit.record(
                AUDIT_COMPLETED,
                Subject.of("document", row.documentId()),
                Map.of("status", "PENDING"),
                Map.of("status", "COMPLETE", "attachmentId", row.attachmentId().toString(), "contentHash", hash),
                ctx);
        events.publish(new AttachmentCompleted(row.attachmentId(), row.documentId(), hash, row.ownerEntityId()));
        return true;
    }

    /** The object never came, came with another hash, or is too large: FAILED with a REVIEW record. */
    boolean fail(Pending row, String why, ScopeContext ctx) {
        requireTransaction();
        int settled = jdbc.update(
                "update kernel.document_attachment set status = 'FAILED', settled_at = ?"
                        + " where attachment_id = ? and status = 'PENDING'",
                Timestamp.from(clock.instant()),
                row.attachmentId());
        if (settled == 0) {
            return false;
        }
        audit.record(
                AUDIT_FAILED,
                Subject.of("document", row.documentId()),
                Map.of("status", "PENDING"),
                Map.of("status", "FAILED", "attachmentId", row.attachmentId().toString()),
                ctx,
                why);
        return true;
    }

    // ---- what the clean-up job does (kernel V0063) --------------------------------------------

    /** A FAILED attachment whose object is still in the store. */
    record Failed(UUID attachmentId, UUID documentId, UUID ownerEntityId, String objectKey) {}

    /**
     * FAILED attachments settled before {@code settledBefore} whose object has not been deleted,
     * the oldest first, at most {@code limit}, under the caller's scope (the job reads as a
     * federation-wide viewer). A row settled before V0063 has no settled_at and counts from the
     * end of its upload window, or from when it was captured.
     */
    List<Failed> failedWithObject(Instant settledBefore, int limit) {
        return jdbc.query(
                """
                select a.attachment_id, a.document_id, d.owner_entity_id, a.object_key
                  from kernel.document_attachment a
                  join kernel.document d on d.document_id = a.document_id
                 where a.status = 'FAILED' and a.object_deleted_at is null
                   and coalesce(a.settled_at, a.upload_expires_at, a.captured_at) < ?
                 order by coalesce(a.settled_at, a.upload_expires_at, a.captured_at)
                 limit ?
                """,
                (rs, rowNum) -> new Failed(
                        rs.getObject("attachment_id", UUID.class),
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getString("object_key")),
                Timestamp.from(settledBefore),
                limit);
    }

    /**
     * The object of a FAILED attachment is gone from the store: the row says when, once, audited.
     * The row itself stays (it is part of the document's history); only the bytes that failed
     * verification are not kept.
     */
    boolean objectDeleted(Failed row, ScopeContext ctx) {
        requireTransaction();
        int changed = jdbc.update(
                "update kernel.document_attachment set object_deleted_at = ?"
                        + " where attachment_id = ? and status = 'FAILED' and object_deleted_at is null",
                Timestamp.from(clock.instant()),
                row.attachmentId());
        if (changed == 0) {
            return false;
        }
        audit.record(
                AUDIT_OBJECT_DELETED,
                Subject.of("document", row.documentId()),
                Map.of("status", "FAILED"),
                Map.of("attachmentId", row.attachmentId().toString(), "objectKey", row.objectKey()),
                ctx,
                "Failed upload kept past its retention");
        return true;
    }

    static String keyOf(UUID ownerEntityId, UUID documentId, UUID attachmentId) {
        return "attachments/" + ownerEntityId + "/" + documentId + "/" + attachmentId;
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Attachments are written inside the handler's transaction");
        }
    }
}
