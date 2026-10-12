package lk.coopfed.knoweb.kernel.internal.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.AttachmentCompleted;
import lk.coopfed.knoweb.kernel.api.Attachments;
import lk.coopfed.knoweb.kernel.api.Attachments.PresignedUpload;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentOrigin;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The attachment flow against PostgreSQL with an in-memory store standing in for S3 (the
 * S3 client itself is proved against MinIO in {@code S3ObjectStoreIntegrationTest}): presign
 * writes PENDING under the document's policies; the verifier completes an uploaded object,
 * fails a wrong hash and an object that never came, and leaves a fresh PENDING one alone; a
 * download URL comes only for a COMPLETE attachment of a document the caller can read.
 *
 * <p>The review of 26 September added: nothing settles while the pre-signed PUT is still
 * valid, and a settled row never changes; asking again for the same attachment renews the URL
 * of a PENDING row and is refused for a settled one or another document's; the size and the
 * type are limited by the register; a counterparty is told it does not own the document; a
 * read URL of anything but an image is a download.
 */
@Import(MemoryObjectStore.class)
class AttachmentsPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID OWNER = UUID.fromString("0190a900-0000-7000-8000-000000000001");
    private static final UUID STRANGER = UUID.fromString("0190a900-0000-7000-8000-000000000002");
    private static final UUID USER = UUID.fromString("0190a900-0000-7000-8000-000000000010");

    @Autowired
    Attachments attachments;

    @Autowired
    AttachmentVerifier verifier;

    @Autowired
    AttachmentCleanupJob cleanup;

    @Autowired
    lk.coopfed.knoweb.kernel.internal.config.JdbcConfigRegistry config;

    @Autowired
    DocumentBaseRepository documents;

    @Autowired
    MemoryObjectStore store;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private UUID documentId;

    @BeforeEach
    void aDraftOfTheOwner() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("delete from kernel.document_attachment");
        admin.execute("delete from kernel.document_state_history");
        admin.execute("delete from kernel.document_line");
        admin.execute("delete from kernel.document");
        store.objects.clear();
        documentId = Ids.next();
        inScope(
                OWNER,
                () -> documents.save(new DocumentRecord(
                        documentId,
                        "ORD",
                        null,
                        null,
                        null,
                        OWNER,
                        STRANGER,
                        null,
                        null,
                        null,
                        "DRAFT",
                        null,
                        null,
                        null,
                        USER,
                        "LKR",
                        null,
                        null,
                        null,
                        null,
                        null,
                        DocumentOrigin.ONLINE,
                        null,
                        null)));
    }

    @Test
    void presignRecordsAPendingAttachmentOnTheCallersOwnDocument() {
        PresignedUpload upload = inScope(
                OWNER, () -> attachments.presignUpload(documentId, null, "image/jpeg", null, null, scope(OWNER)));

        assertThat(upload.objectKey())
                .isEqualTo("attachments/" + OWNER + "/" + documentId + "/" + upload.attachmentId());
        assertThat(upload.url().toString()).contains(upload.objectKey());
        assertThat(inScope(OWNER, () -> attachments.status(upload.attachmentId())))
                .contains("PENDING");
        assertThat(kernel.committedAudit()).extracting(r -> r.eventType()).containsExactly("ATTACHMENT_PRESIGNED");

        // No URL yet: nothing is COMPLETE.
        assertThat(inScope(OWNER, () -> attachments.presignDownload(upload.attachmentId(), scope(OWNER))))
                .isEmpty();
    }

    @Test
    void aStrangerCannotAttachAndTheCounterpartyCannotEither() {
        assertThatThrownBy(() -> inScope(
                        UUID.fromString("0190a900-0000-7000-8000-000000000003"),
                        () -> attachments.presignUpload(documentId, null, "image/jpeg", null, null, scope(STRANGER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("attachment.document_not_found");

        // The counterparty reads the document (party_read) but does not own it: said so, before
        // the row's policy would refuse the insert with a bare database error.
        assertThatThrownBy(() -> inScope(
                        STRANGER,
                        () -> attachments.presignUpload(documentId, null, "image/jpeg", null, null, scope(STRANGER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("attachment.document_not_owned");

        assertThatThrownBy(() -> inScope(
                        OWNER,
                        () -> attachments.presignUpload(documentId, null, "not a type", null, null, scope(OWNER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("attachment.content_type_invalid");
    }

    @Test
    void theVerifierCompletesAnUploadedObjectWithTheRightHash() {
        byte[] bytes = "a photograph".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(bytes);

        PresignedUpload upload = inScope(
                OWNER, () -> attachments.presignUpload(documentId, null, "image/jpeg", null, hash, scope(OWNER)));
        kernel.reset();

        // Nothing uploaded yet, and it is fresh: it stays PENDING.
        assertThat(verifier.verifyPending()).isZero();
        assertThat(inScope(OWNER, () -> attachments.status(upload.attachmentId())))
                .contains("PENDING");

        store.objects.put(upload.objectKey(), bytes);
        // Uploaded, but the pre-signed PUT is still valid: the bytes could still be replaced,
        // so nothing settles yet.
        assertThat(verifier.verifyPending()).isZero();
        assertThat(inScope(OWNER, () -> attachments.status(upload.attachmentId())))
                .contains("PENDING");

        windowOver(upload.attachmentId());
        assertThat(verifier.verifyPending()).isEqualTo(1);

        assertThat(inScope(OWNER, () -> attachments.status(upload.attachmentId())))
                .contains("COMPLETE");
        assertThat(kernel.committedAudit()).extracting(r -> r.eventType()).containsExactly("ATTACHMENT_COMPLETED");
        assertThat(kernel.committedEvents())
                .hasSize(1)
                .first()
                .isInstanceOfSatisfying(AttachmentCompleted.class, event -> {
                    assertThat(event.attachmentId()).isEqualTo(upload.attachmentId());
                    assertThat(event.contentHash()).isEqualTo(hash);
                });

        // Now the owner gets a URL, the counterparty too (it reads the document), a stranger nothing.
        assertThat(inScope(OWNER, () -> attachments.presignDownload(upload.attachmentId(), scope(OWNER))))
                .isPresent();
        assertThat(inScope(STRANGER, () -> attachments.presignDownload(upload.attachmentId(), scope(STRANGER))))
                .isPresent();
        assertThat(inScope(
                        UUID.fromString("0190a900-0000-7000-8000-000000000003"),
                        () -> attachments.presignDownload(upload.attachmentId(), scope(STRANGER))))
                .isEmpty();
        // An image is rendered; the URL carries no download disposition.
        assertThat(inScope(OWNER, () -> attachments.presignDownload(upload.attachmentId(), scope(OWNER)))
                        .orElseThrow()
                        .toString())
                .doesNotContain("disposition=attachment");

        // Settled: a second run finds nothing, and nobody, not even the owner, changes the row.
        kernel.reset();
        assertThat(verifier.verifyPending()).isZero();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThatThrownBy(() -> inScope(
                        OWNER,
                        () -> jdbc.update(
                                "update kernel.document_attachment set status = 'FAILED' where attachment_id = ?",
                                upload.attachmentId())))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("attachment.immutable");
        // And no new URL is issued for it.
        assertThatThrownBy(() -> inScope(
                        OWNER,
                        () -> attachments.presignUpload(
                                documentId, upload.attachmentId(), "image/jpeg", null, hash, scope(OWNER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("attachment.not_pending");
    }

    @Test
    void askingAgainForTheSameAttachmentRenewsTheUrlOfThePendingRow() {
        UUID attachmentId = Ids.next();
        PresignedUpload first = inScope(
                OWNER,
                () -> attachments.presignUpload(documentId, attachmentId, "image/jpeg", 12L, null, scope(OWNER)));
        superuserJdbc()
                .update(
                        "update kernel.document_attachment set upload_expires_at = now() + interval '1 minute'"
                                + " where attachment_id = ?",
                        attachmentId);

        // The till lost its connection and asks again with the same id while its window is still
        // open (doc 32 section 4): the same row, the same key, a fresh window; still one PENDING
        // row and no duplicate key.
        PresignedUpload again = inScope(
                OWNER,
                () -> attachments.presignUpload(documentId, attachmentId, "image/jpeg", 12L, null, scope(OWNER)));
        assertThat(again.attachmentId()).isEqualTo(attachmentId);
        assertThat(again.objectKey()).isEqualTo(first.objectKey());
        assertThat(again.expiresAt()).isAfter(first.expiresAt().minusSeconds(1));
        assertThat(again.expiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(2)));
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.document_attachment where document_id = ?",
                                Long.class,
                                documentId))
                .isEqualTo(1L);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select upload_expires_at > now() from kernel.document_attachment where attachment_id = ?",
                                Boolean.class,
                                attachmentId))
                .isTrue();
        assertThat(kernel.committedAudit())
                .extracting(r -> r.eventType())
                .containsExactly("ATTACHMENT_PRESIGNED", "ATTACHMENT_PRESIGNED");

        // Once the window has ended the verifier may be hashing the bytes: no new URL, and the
        // announced hash stays what it was.
        windowOver(attachmentId);
        kernel.reset();
        assertThatThrownBy(() -> inScope(
                        OWNER,
                        () -> attachments.presignUpload(
                                documentId, attachmentId, "image/jpeg", 12L, "a".repeat(64), scope(OWNER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("attachment.upload_expired");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select content_hash is null and upload_expires_at < now()"
                                        + " from kernel.document_attachment where attachment_id = ?",
                                Boolean.class,
                                attachmentId))
                .isTrue();

        // The same id on another document of the owner is refused.
        UUID otherDocument = Ids.next();
        inScope(OWNER, () -> documents.save(draft(otherDocument)));
        assertThatThrownBy(() -> inScope(
                        OWNER,
                        () -> attachments.presignUpload(
                                otherDocument, attachmentId, "image/jpeg", null, null, scope(OWNER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("attachment.document_mismatch");
    }

    @Test
    void theSizeAndTheTypeAreLimitedByTheRegister() {
        // The declared size is checked at presign (and signed into the URL) ...
        assertThatThrownBy(() -> inScope(
                        OWNER,
                        () -> attachments.presignUpload(
                                documentId, null, "image/jpeg", 6L * 1024 * 1024, null, scope(OWNER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("attachment.too_large");
        assertThat(inScope(
                                OWNER,
                                () -> attachments.presignUpload(
                                        documentId, null, "image/jpeg", 300L * 1024, null, scope(OWNER)))
                        .url()
                        .toString())
                .contains("length=307200");

        // ... and the stored size by the verifier, before the object is read.
        PresignedUpload big = inScope(
                OWNER, () -> attachments.presignUpload(documentId, null, "image/jpeg", null, null, scope(OWNER)));
        kernel.reset();
        store.objects.put(big.objectKey(), new byte[6 * 1024 * 1024]);
        windowOver(big.attachmentId());
        assertThat(verifier.verifyPending()).isEqualTo(1);
        assertThat(inScope(OWNER, () -> attachments.status(big.attachmentId()))).contains("FAILED");

        // A type the register does not list is refused; a well-formed one that is listed passes.
        assertThatThrownBy(() -> inScope(
                        OWNER,
                        () -> attachments.presignUpload(documentId, null, "text/html", null, null, scope(OWNER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("attachment.content_type_not_allowed");
    }

    @Test
    void anythingButAnImageIsDownloadedNotRendered() {
        byte[] bytes = "%PDF-1.4".getBytes(StandardCharsets.UTF_8);
        PresignedUpload pdf = inScope(
                OWNER,
                () -> attachments.presignUpload(
                        documentId, null, "application/pdf", null, sha256(bytes), scope(OWNER)));
        store.objects.put(pdf.objectKey(), bytes);
        windowOver(pdf.attachmentId());
        assertThat(verifier.verifyPending()).isEqualTo(1);
        assertThat(inScope(OWNER, () -> attachments.presignDownload(pdf.attachmentId(), scope(OWNER)))
                        .orElseThrow()
                        .toString())
                .contains("disposition=attachment");
    }

    @Test
    void theVerifierFailsAWrongHashAndAnObjectThatNeverCame() {
        PresignedUpload wrong = inScope(
                OWNER,
                () -> attachments.presignUpload(
                        documentId,
                        null,
                        "image/jpeg",
                        null,
                        sha256("what was announced".getBytes(StandardCharsets.UTF_8)),
                        scope(OWNER)));
        PresignedUpload never = inScope(
                OWNER, () -> attachments.presignUpload(documentId, null, "image/png", null, null, scope(OWNER)));
        kernel.reset();

        store.objects.put(wrong.objectKey(), "what arrived".getBytes(StandardCharsets.UTF_8));
        windowOver(wrong.attachmentId());
        superuserJdbc()
                .update(
                        "update kernel.document_attachment set captured_at = now() - interval '2 days',"
                                + " upload_expires_at = now() - interval '2 days' where attachment_id = ?",
                        never.attachmentId());

        assertThat(verifier.verifyPending()).isEqualTo(2);

        assertThat(inScope(OWNER, () -> attachments.status(wrong.attachmentId())))
                .contains("FAILED");
        assertThat(inScope(OWNER, () -> attachments.status(never.attachmentId())))
                .contains("FAILED");
        assertThat(kernel.committedAudit())
                .extracting(r -> r.eventType())
                .containsExactly("ATTACHMENT_FAILED", "ATTACHMENT_FAILED");
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void theCleanUpDeletesTheFileOfAFailedUploadAfterItsRetentionAndNeverACompleteOne() {
        byte[] photo = "a photograph".getBytes(StandardCharsets.UTF_8);
        PresignedUpload good = inScope(
                OWNER,
                () -> attachments.presignUpload(documentId, null, "image/jpeg", null, sha256(photo), scope(OWNER)));
        PresignedUpload wrong = inScope(
                OWNER,
                () -> attachments.presignUpload(
                        documentId,
                        null,
                        "image/jpeg",
                        null,
                        sha256("what was announced".getBytes(StandardCharsets.UTF_8)),
                        scope(OWNER)));
        store.objects.put(good.objectKey(), photo);
        store.objects.put(wrong.objectKey(), "what arrived".getBytes(StandardCharsets.UTF_8));
        windowOver(good.attachmentId());
        windowOver(wrong.attachmentId());
        assertThat(verifier.verifyPending()).isEqualTo(2);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.document_attachment where settled_at is not null",
                                Integer.class))
                .isEqualTo(2);
        kernel.reset();

        // Inside the retention (attachment.failed_retention_days, 30 by default) the file is kept.
        assertThat(cleanup.deleteFailedObjects()).isZero();
        assertThat(store.objects).containsKey(wrong.objectKey());

        // Settled 31 days ago, both: only the FAILED one's file goes, once, audited; the rows stay.
        superuserJdbc()
                .execute("begin; set local session_replication_role = replica;"
                        + " update kernel.document_attachment set settled_at = now() - interval '31 days'"
                        + " where document_id = '" + documentId + "'; commit;");
        assertThat(cleanup.deleteFailedObjects()).isEqualTo(1);
        assertThat(store.objects).doesNotContainKey(wrong.objectKey()).containsKey(good.objectKey());
        assertThat(kernel.committedAudit()).extracting(r -> r.eventType()).containsExactly("ATTACHMENT_OBJECT_DELETED");
        assertThat(superuserJdbc()
                        .queryForMap(
                                "select status, object_deleted_at is not null as gone from kernel.document_attachment"
                                        + " where attachment_id = ?",
                                wrong.attachmentId()))
                .containsEntry("status", "FAILED")
                .containsEntry("gone", true);
        assertThat(inScope(OWNER, () -> attachments.status(good.attachmentId())))
                .contains("COMPLETE");

        kernel.reset();
        assertThat(cleanup.deleteFailedObjects()).isZero();
        assertThat(kernel.committedAudit()).isEmpty();

        // The one change a settled row admits happens once, on a FAILED row only (kernel V0063).
        assertThatThrownBy(() -> inScope(
                        OWNER,
                        () -> jdbc.update(
                                "update kernel.document_attachment set object_deleted_at = now() where attachment_id = ?",
                                wrong.attachmentId())))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("attachment.immutable");
        assertThatThrownBy(() -> inScope(
                        OWNER,
                        () -> jdbc.update(
                                "update kernel.document_attachment set object_deleted_at = now() where attachment_id = ?",
                                good.attachmentId())))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("attachment.immutable");
    }

    @Test
    void anUploadUrlLastsWhatTheRegisterSays() {
        superuserJdbc()
                .update(
                        "insert into kernel.config_value (key, scope_entity_id, scope_location_id, value, changed_by)"
                                + " values ('attachment.upload_url_minutes', null, null, '5'::jsonb, ?)",
                        USER);
        config.invalidate("attachment.upload_url_minutes");
        try {
            PresignedUpload upload = inScope(
                    OWNER, () -> attachments.presignUpload(documentId, null, "image/jpeg", null, null, scope(OWNER)));
            assertThat(upload.expiresAt())
                    .isBetween(
                            java.time.Instant.now().plusSeconds(4 * 60),
                            java.time.Instant.now().plusSeconds(5 * 60 + 1));
            assertThat(superuserJdbc()
                            .queryForObject(
                                    "select upload_expires_at < now() + interval '6 minutes'"
                                            + " from kernel.document_attachment where attachment_id = ?",
                                    Boolean.class,
                                    upload.attachmentId()))
                    .isTrue();
        } finally {
            superuserJdbc().update("delete from kernel.config_value where key = 'attachment.upload_url_minutes'");
            config.invalidate("attachment.upload_url_minutes");
        }
    }

    @Test
    void anAnnouncedHashIsCheckedForShape() {
        assertThatThrownBy(() -> inScope(
                        OWNER,
                        () -> attachments.presignUpload(documentId, null, "image/jpeg", null, "abc", scope(OWNER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("attachment.hash_invalid");
    }

    /** The pre-signed PUT of the attachment has expired: the verifier may settle it. */
    private void windowOver(UUID attachmentId) {
        superuserJdbc()
                .update(
                        "update kernel.document_attachment set upload_expires_at = now() - interval '1 minute'"
                                + " where attachment_id = ?",
                        attachmentId);
    }

    private static DocumentRecord draft(UUID id) {
        return new DocumentRecord(
                id,
                "ORD",
                null,
                null,
                null,
                OWNER,
                STRANGER,
                null,
                null,
                null,
                "DRAFT",
                null,
                null,
                null,
                USER,
                "LKR",
                null,
                null,
                null,
                null,
                null,
                DocumentOrigin.ONLINE,
                null,
                null);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ScopeContext scope(UUID entity) {
        Scope active = new Scope(entity, null);
        return new ScopeContext(
                USER,
                null,
                entity,
                List.of(active),
                active,
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.ENGLISH,
                Ids.next());
    }

    private <T> T inScope(UUID entity, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForList(
                    "select set_config('app.user_id', ?, true), set_config('app.correlation_id', ?, true),"
                            + " set_config('app.scope_entity_id', ?, true), set_config('app.scope_location_id', '', true),"
                            + " set_config('app.scope_class', 'OWN', true), set_config('app.granted_entities', '{}', true)",
                    USER.toString(),
                    Ids.next().toString(),
                    entity.toString());
            return work.get();
        });
    }
}
