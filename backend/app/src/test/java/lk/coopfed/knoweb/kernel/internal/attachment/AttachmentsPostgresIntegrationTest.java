package lk.coopfed.knoweb.kernel.internal.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
 */
@Import(AttachmentsPostgresIntegrationTest.MemoryStore.class)
class AttachmentsPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID OWNER = UUID.fromString("0190a900-0000-7000-8000-000000000001");
    private static final UUID STRANGER = UUID.fromString("0190a900-0000-7000-8000-000000000002");
    private static final UUID USER = UUID.fromString("0190a900-0000-7000-8000-000000000010");

    @Autowired
    Attachments attachments;

    @Autowired
    AttachmentVerifier verifier;

    @Autowired
    DocumentBaseRepository documents;

    @Autowired
    MemoryStore store;

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
        PresignedUpload upload =
                inScope(OWNER, () -> attachments.presignUpload(documentId, null, "image/jpeg", null, scope(OWNER)));

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
                        () -> attachments.presignUpload(documentId, null, "image/jpeg", null, scope(STRANGER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("attachment.document_not_found");

        // The counterparty reads the document (party_read) but may not write its rows.
        assertThatThrownBy(() -> inScope(
                        STRANGER,
                        () -> attachments.presignUpload(documentId, null, "image/jpeg", null, scope(STRANGER))))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> inScope(
                        OWNER, () -> attachments.presignUpload(documentId, null, "not a type", null, scope(OWNER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("attachment.content_type_invalid");
    }

    @Test
    void theVerifierCompletesAnUploadedObjectWithTheRightHash() {
        byte[] bytes = "a photograph".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(bytes);

        PresignedUpload upload =
                inScope(OWNER, () -> attachments.presignUpload(documentId, null, "image/jpeg", hash, scope(OWNER)));
        kernel.reset();

        // Nothing uploaded yet, and it is fresh: it stays PENDING.
        assertThat(verifier.verifyPending()).isZero();
        assertThat(inScope(OWNER, () -> attachments.status(upload.attachmentId())))
                .contains("PENDING");

        store.objects.put(upload.objectKey(), bytes);
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
    }

    @Test
    void theVerifierFailsAWrongHashAndAnObjectThatNeverCame() {
        PresignedUpload wrong = inScope(
                OWNER,
                () -> attachments.presignUpload(
                        documentId,
                        null,
                        "image/jpeg",
                        sha256("what was announced".getBytes(StandardCharsets.UTF_8)),
                        scope(OWNER)));
        PresignedUpload never =
                inScope(OWNER, () -> attachments.presignUpload(documentId, null, "image/png", null, scope(OWNER)));
        kernel.reset();

        store.objects.put(wrong.objectKey(), "what arrived".getBytes(StandardCharsets.UTF_8));
        superuserJdbc()
                .update(
                        "update kernel.document_attachment set captured_at = now() - interval '2 days' where attachment_id = ?",
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
    void anAnnouncedHashIsCheckedForShape() {
        assertThatThrownBy(() -> inScope(
                        OWNER, () -> attachments.presignUpload(documentId, null, "image/jpeg", "abc", scope(OWNER))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("attachment.hash_invalid");
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

    /** Objects in a map: what the verifier asks of a store, without a store. */
    @TestConfiguration(proxyBeanMethods = false)
    static class MemoryStore implements ObjectStore {

        final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Bean
        @Primary
        ObjectStore memoryObjectStore() {
            return this;
        }

        @Override
        public URI presignPut(String key, String contentType, Duration validFor) {
            return URI.create("memory://put/" + key + "?type=" + contentType);
        }

        @Override
        public URI presignGet(String key, Duration validFor) {
            return URI.create("memory://get/" + key);
        }

        @Override
        public Optional<Long> head(String key) {
            return Optional.ofNullable(objects.get(key)).map(bytes -> (long) bytes.length);
        }

        @Override
        public String sha256Hex(String key) {
            return sha256(objects.get(key));
        }
    }
}
