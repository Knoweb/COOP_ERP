package lk.coopfed.knoweb.kernel.internal.attachment;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.kernel.api.AttachmentCompleted;
import lk.coopfed.knoweb.kernel.api.Attachments;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
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
 */
@Component
class AttachmentService implements Attachments {

    static final String AUDIT_PRESIGNED = "ATTACHMENT_PRESIGNED";
    static final String AUDIT_COMPLETED = "ATTACHMENT_COMPLETED";
    static final String AUDIT_FAILED = "ATTACHMENT_FAILED";

    private static final Pattern CONTENT_TYPE = Pattern.compile("[a-z]+/[a-z0-9.+-]+");
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private final JdbcTemplate jdbc;
    private final ObjectStore store;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;
    private final Duration presignFor;

    AttachmentService(
            JdbcTemplate jdbc,
            ObjectStore store,
            AuditFacade audit,
            EventPublisher events,
            Clock clock,
            @Value("${coop-erp.object-store.presign-minutes}") int presignMinutes) {
        this.jdbc = jdbc;
        this.store = store;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.presignFor = Duration.ofMinutes(presignMinutes);
    }

    @Override
    public PresignedUpload presignUpload(
            UUID documentId, UUID attachmentId, String contentType, String sha256Hex, ScopeContext ctx) {
        requireTransaction();

        if (contentType == null || !CONTENT_TYPE.matcher(contentType).matches()) {
            throw new ProblemException(
                    "attachment.content_type_invalid", Map.of("contentType", String.valueOf(contentType)));
        }

        String hash = sha256Hex == null ? null : sha256Hex.strip().toLowerCase();

        if (hash != null && !SHA256_HEX.matcher(hash).matches()) {
            throw new ProblemException("attachment.hash_invalid");
        }

        // The document must be the caller's: a document the caller cannot see is "not found",
        // and one it can see but does not own is refused by the row's policy below.
        UUID ownerEntityId = jdbc
                .query(
                        "select owner_entity_id from kernel.document where document_id = ?",
                        (rs, rowNum) -> rs.getObject("owner_entity_id", UUID.class),
                        documentId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ProblemException("attachment.document_not_found"));

        UUID id = attachmentId == null ? Ids.next() : attachmentId;
        String key = keyOf(ownerEntityId, documentId, id);
        Instant now = clock.instant();

        jdbc.update(
                """
                insert into kernel.document_attachment (
                    attachment_id, document_id, object_key, content_hash, status, captured_at, captured_by, device_id
                ) values (?, ?, ?, ?, 'PENDING', ?, ?, ?)
                """,
                id,
                documentId,
                key,
                hash,
                Timestamp.from(now),
                ctx == null ? null : ctx.userId(),
                ctx == null ? null : ctx.deviceId());

        URI url = store.presignPut(key, contentType, presignFor);

        audit.record(
                AUDIT_PRESIGNED,
                Subject.of("document", documentId),
                null,
                Map.of("attachmentId", id.toString(), "contentType", contentType, "objectKey", key),
                ctx);

        return new PresignedUpload(id, url, now.plus(presignFor), key);
    }

    @Override
    public Optional<URI> presignDownload(UUID attachmentId, ScopeContext ctx) {
        return jdbc
                .query(
                        "select object_key from kernel.document_attachment where attachment_id = ? and status = 'COMPLETE'",
                        (rs, rowNum) -> rs.getString("object_key"),
                        attachmentId)
                .stream()
                .findFirst()
                .map(key -> store.presignGet(key, presignFor));
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

    // ---- what the verifier does, in the OWN scope of the document's entity ---------------------

    /** A PENDING row, with what the verifier needs to know about it. */
    record Pending(
            UUID attachmentId,
            UUID documentId,
            UUID ownerEntityId,
            String objectKey,
            String expectedHash,
            Instant capturedAt) {}

    /** Every PENDING attachment, under the caller's scope (the verifier reads as a federation-wide viewer). */
    List<Pending> pending() {
        return jdbc.query(
                """
                select a.attachment_id, a.document_id, d.owner_entity_id, a.object_key, a.content_hash, a.captured_at
                  from kernel.document_attachment a
                  join kernel.document d on d.document_id = a.document_id
                 where a.status = 'PENDING'
                 order by a.captured_at
                """,
                (rs, rowNum) -> new Pending(
                        rs.getObject("attachment_id", UUID.class),
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getString("object_key"),
                        rs.getString("content_hash"),
                        rs.getTimestamp("captured_at").toInstant()));
    }

    /** The object is there and its hash is right: COMPLETE, audited, published. */
    void complete(Pending row, String hash, ScopeContext ctx) {
        requireTransaction();
        jdbc.update(
                "update kernel.document_attachment set status = 'COMPLETE', content_hash = ? where attachment_id = ?",
                hash,
                row.attachmentId());
        audit.record(
                AUDIT_COMPLETED,
                Subject.of("document", row.documentId()),
                Map.of("status", "PENDING"),
                Map.of("status", "COMPLETE", "attachmentId", row.attachmentId().toString(), "contentHash", hash),
                ctx);
        events.publish(new AttachmentCompleted(row.attachmentId(), row.documentId(), hash, row.ownerEntityId()));
    }

    /** The object never came, or came with another hash: FAILED with a REVIEW record. */
    void fail(Pending row, String why, ScopeContext ctx) {
        requireTransaction();
        jdbc.update(
                "update kernel.document_attachment set status = 'FAILED' where attachment_id = ?", row.attachmentId());
        audit.record(
                AUDIT_FAILED,
                Subject.of("document", row.documentId()),
                Map.of("status", "PENDING"),
                Map.of("status", "FAILED", "attachmentId", row.attachmentId().toString()),
                ctx,
                why);
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
