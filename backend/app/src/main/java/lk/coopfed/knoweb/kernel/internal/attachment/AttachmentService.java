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

    private static final Pattern CONTENT_TYPE = Pattern.compile("[a-z]+/[a-z0-9.+-]+");
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private final JdbcTemplate jdbc;
    private final ObjectStore store;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final ConfigRegistry config;
    private final Clock clock;
    private final Duration presignFor;

    AttachmentService(
            JdbcTemplate jdbc,
            ObjectStore store,
            AuditFacade audit,
            EventPublisher events,
            ConfigRegistry config,
            Clock clock,
            @Value("${coop-erp.object-store.presign-minutes}") int presignMinutes) {
        this.jdbc = jdbc;
        this.store = store;
        this.audit = audit;
        this.events = events;
        this.config = config;
        this.clock = clock;
        this.presignFor = Duration.ofMinutes(presignMinutes);
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
        Instant expiresAt = now.plus(presignFor);

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

        URI url = store.presignPut(key, contentType, contentLength, presignFor);

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
                                store.presignGet(rs.getString("object_key"), rs.getString("content_type"), presignFor),
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
                "update kernel.document_attachment set status = 'COMPLETE', content_hash = ?"
                        + " where attachment_id = ? and status = 'PENDING'",
                hash,
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
                "update kernel.document_attachment set status = 'FAILED' where attachment_id = ? and status = 'PENDING'",
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

    static String keyOf(UUID ownerEntityId, UUID documentId, UUID attachmentId) {
        return "attachments/" + ownerEntityId + "/" + documentId + "/" + attachmentId;
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Attachments are written inside the handler's transaction");
        }
    }
}
