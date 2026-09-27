package lk.coopfed.knoweb.kernel.internal.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ObjectStorage;
import lk.coopfed.knoweb.kernel.api.ObjectStorage.Outcome;
import lk.coopfed.knoweb.kernel.api.ObjectStorage.Verification;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ObjectStorage} and its upload ledger, {@code kernel.object_upload} (kernel V0060;
 * CR-19A-7 as revised on 27 September 2026), against PostgreSQL with the bytes in memory: the
 * K-09 rules held once, in the kernel, for every module that owns objects without a document.
 * A presign records PENDING under the register's limits; nothing settles while the PUT URL is
 * valid; verify settles VERIFIED or FAILED once, audited; a settled key gets no new URL; the
 * verified original is never written; nothing is read, derived or served from an unverified
 * object; the entity in the key is the caller's on every method; another module's key is
 * refused.
 */
@Import(MemoryObjectStore.class)
class ObjectStoragePostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID OWNER = UUID.fromString("0190a901-0000-7000-8000-000000000001");
    private static final UUID STRANGER = UUID.fromString("0190a901-0000-7000-8000-000000000002");
    private static final UUID USER = UUID.fromString("0190a901-0000-7000-8000-000000000010");

    @Autowired
    ObjectStorage storage;

    @Autowired
    MemoryObjectStore store;

    @Autowired
    AttachmentCleanupJob cleanup;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final byte[] bytes = "a product photo".getBytes(StandardCharsets.UTF_8);
    private String key;

    @BeforeEach
    void clean() {
        superuserJdbc().execute("delete from kernel.object_upload where owner_module = 'kernel'");
        store.objects.clear();
        key = ObjectStorage.keyOf(ObjectStoragePostgresIntegrationTest.class, OWNER, Ids.next());
        kernel.reset();
    }

    @Test
    void theKeyNamesTheCallersModuleAndOnlyItsOwn() {
        UUID id = Ids.next();
        assertThat(ObjectStorage.keyOf(ObjectStoragePostgresIntegrationTest.class, OWNER, id))
                .isEqualTo("objects/kernel/" + OWNER + "/" + id);
        // A module anchors a key on a class of its own, never on another module's.
        assertThatThrownBy(() -> ObjectStorage.keyOf(lk.coopfed.knoweb.m2catalogue.api.AttachImage.class, OWNER, id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("m2catalogue");
        // Nor writes another module's key by hand.
        String m2Key = "objects/m2catalogue/" + OWNER + "/" + id;
        assertThatThrownBy(() -> inScope(OWNER, () -> presign(m2Key)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("may not use an object of module m2catalogue");
        assertThatThrownBy(() -> storage.verify(m2Key, own(OWNER))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.presignGet(m2Key, "image/png", own(OWNER)))
                .isInstanceOf(IllegalArgumentException.class);
        // A document's attachment key is not a module object key.
        assertThatThrownBy(() -> inScope(OWNER, () -> presign("attachments/" + OWNER + "/" + id + "/" + id)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPresignRecordsAPendingRowUnderTheRegistersLimits() {
        ObjectStorage.PresignedPut put = inScope(OWNER, () -> presign(key));

        assertThat(put.objectKey()).isEqualTo(key);
        assertThat(put.url().toString()).contains(key).contains("length=" + bytes.length);
        assertThat(ledger(key)).satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("owner_module")).isEqualTo("kernel");
            assertThat(row.get("owner_entity_id")).isEqualTo(OWNER);
            assertThat(row.get("content_type")).isEqualTo("image/png");
            assertThat(row.get("content_hash")).isEqualTo(MemoryObjectStore.sha256(bytes));
            assertThat(row.get("settled_at")).isNull();
        });

        // Asking again while PENDING renews the URL of the same row.
        assertThat(inScope(OWNER, () -> presign(key)).objectKey()).isEqualTo(key);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.object_upload where object_key = ?", Integer.class, key))
                .isEqualTo(1);
        refused(
                () -> inScope(OWNER, () -> storage.presignPut(key, "image/jpeg", null, null, own(OWNER))),
                "object.content_type_mismatch");

        // The register's limits, as for a document's attachment.
        String other = ObjectStorage.keyOf(ObjectStoragePostgresIntegrationTest.class, OWNER, Ids.next());
        refused(
                () -> inScope(OWNER, () -> storage.presignPut(other, "text/html", null, null, own(OWNER))),
                "attachment.content_type_not_allowed");
        refused(
                () -> inScope(OWNER, () -> storage.presignPut(other, "image/png", 1L << 40, null, own(OWNER))),
                "attachment.too_large");
        refused(
                () -> inScope(OWNER, () -> storage.presignPut(other, "image/png", null, "abc", own(OWNER))),
                "attachment.hash_invalid");

        // The ledger row is written in the caller's transaction, so there must be one.
        assertThatThrownBy(() -> storage.presignPut(other, "image/png", null, null, own(OWNER)))
                .isInstanceOf(IllegalStateException.class);
        // The module audits its own row; the presign audits nothing.
        assertThat(kernel.committedAudit()).isEmpty();
    }

    @Test
    void verifyAnswersPendingWhileThePutUrlIsValidThenSettlesOnceAudited() {
        inScope(OWNER, () -> presign(key));
        store.objects.put(key, bytes);

        // The same URL could still replace the bytes: nothing is settled, nothing audited.
        assertThat(storage.verify(key, own(OWNER))).isEqualTo(new Verification(Outcome.PENDING, null, null, false));
        assertThat(ledger(key).get("status")).isEqualTo("PENDING");
        assertThat(kernel.committedAudit()).isEmpty();

        windowOver(key);
        Verification verified = storage.verify(key, own(OWNER));
        assertThat(verified)
                .isEqualTo(
                        new Verification(Outcome.VERIFIED, (long) bytes.length, MemoryObjectStore.sha256(bytes), true));
        assertThat(ledger(key)).satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("VERIFIED");
            assertThat(row.get("content_length")).isEqualTo((long) bytes.length);
            assertThat(row.get("settled_at")).isNotNull();
        });
        assertThat(audit("OBJECT_VERIFIED")).singleElement().satisfies(record -> {
            assertThat(record.subject().type()).isEqualTo("object");
            assertThat(record.subject().id().toString()).isEqualTo(key.substring(key.lastIndexOf('/') + 1));
            assertThat(((Map<?, ?>) record.before()).get("status")).isEqualTo("PENDING");
            assertThat(((Map<?, ?>) record.after()).get("status")).isEqualTo("VERIFIED");
            assertThat(((Map<?, ?>) record.after()).get("objectKey")).isEqualTo(key);
        });
        assertThat(kernel.committedEvents()).isEmpty();

        // Asked again, a settled row answers what it was settled with, and audits nothing twice.
        kernel.reset();
        store.objects.put(key, "replaced".getBytes(StandardCharsets.UTF_8));
        assertThat(storage.verify(key, own(OWNER))).isEqualTo(verified);
        assertThat(kernel.committedAudit()).isEmpty();
    }

    @Test
    void aWrongHashATooLargeObjectAndAnObjectThatNeverCameFailAudited() {
        String wrongHash = key;
        String tooLarge = ObjectStorage.keyOf(ObjectStoragePostgresIntegrationTest.class, OWNER, Ids.next());
        String neverCame = ObjectStorage.keyOf(ObjectStoragePostgresIntegrationTest.class, OWNER, Ids.next());
        String notYet = ObjectStorage.keyOf(ObjectStoragePostgresIntegrationTest.class, OWNER, Ids.next());
        inScope(OWNER, () -> {
            presign(wrongHash);
            storage.presignPut(tooLarge, "image/png", null, null, own(OWNER));
            presign(neverCame);
            return presign(notYet);
        });
        store.objects.put(wrongHash, "other bytes".getBytes(StandardCharsets.UTF_8));
        store.objects.put(tooLarge, new byte[6 * 1024 * 1024]);
        for (String k : List.of(wrongHash, tooLarge, neverCame, notYet)) {
            windowOver(k);
        }
        // The trigger keeps created_at for ever; the test moves it back with the trigger off.
        superuserJdbc()
                .execute("begin; set local session_replication_role = replica;"
                        + " update kernel.object_upload set created_at = now() - interval '25 hours'"
                        + " where object_key = '" + neverCame + "'; commit;");

        assertThat(storage.verify(wrongHash, own(OWNER))).satisfies(v -> {
            assertThat(v.outcome()).isEqualTo(Outcome.HASH_MISMATCH);
            assertThat(v.settled()).isTrue();
        });
        assertThat(storage.verify(tooLarge, own(OWNER)).outcome()).isEqualTo(Outcome.TOO_LARGE);
        assertThat(storage.verify(neverCame, own(OWNER)))
                .isEqualTo(new Verification(Outcome.MISSING, null, null, true));
        // Inside the kernel's upload window a missing object is only not there yet.
        assertThat(storage.verify(notYet, own(OWNER))).isEqualTo(new Verification(Outcome.MISSING, null, null, false));

        assertThat(ledger(wrongHash).get("failure")).isEqualTo("HASH_MISMATCH");
        assertThat(ledger(tooLarge).get("failure")).isEqualTo("TOO_LARGE");
        assertThat(ledger(neverCame).get("failure")).isEqualTo("MISSING");
        assertThat(ledger(notYet).get("status")).isEqualTo("PENDING");
        assertThat(audit("OBJECT_FAILED"))
                .hasSize(3)
                .extracting(KernelRecorder.AuditRecord::reason)
                .anySatisfy(reason -> assertThat(reason).startsWith("hash mismatch"))
                .anySatisfy(reason -> assertThat(reason).startsWith("too large"))
                .anySatisfy(reason -> assertThat(reason).startsWith("not uploaded within"));

        // A failed object is read, derived and served from never.
        refused(() -> storage.read(wrongHash, own(OWNER)), "object.not_verified");
        refused(() -> storage.presignGet(wrongHash, "image/png", own(OWNER)), "object.not_verified");
    }

    @Test
    void theCleanUpDeletesAFailedObjectAfterItsRetentionAndNeverAVerifiedOne() {
        String wrongHash = ObjectStorage.keyOf(ObjectStoragePostgresIntegrationTest.class, OWNER, Ids.next());
        verified(key);
        inScope(OWNER, () -> presign(wrongHash));
        store.objects.put(wrongHash, "other bytes".getBytes(StandardCharsets.UTF_8));
        windowOver(wrongHash);
        assertThat(storage.verify(wrongHash, own(OWNER)).outcome()).isEqualTo(Outcome.HASH_MISMATCH);
        kernel.reset();

        // Inside the retention the file is kept.
        assertThat(cleanup.deleteFailedObjects()).isZero();
        assertThat(store.objects).containsKey(wrongHash);

        superuserJdbc()
                .execute("begin; set local session_replication_role = replica;"
                        + " update kernel.object_upload set settled_at = now() - interval '31 days'"
                        + " where object_key in ('" + key + "', '" + wrongHash + "'); commit;");
        assertThat(cleanup.deleteFailedObjects()).isEqualTo(1);
        assertThat(store.objects).doesNotContainKey(wrongHash).containsKey(key);
        assertThat(audit("OBJECT_DELETED"))
                .extracting(KernelRecorder.AuditRecord::subject)
                .containsExactly(lk.coopfed.knoweb.kernel.api.Subject.of(
                        "object", UUID.fromString(wrongHash.substring(wrongHash.lastIndexOf('/') + 1))));
        assertThat(ledger(wrongHash))
                .containsEntry("status", "FAILED")
                .containsEntry("failure", "HASH_MISMATCH")
                .hasEntrySatisfying(
                        "object_deleted_at", deletedAt -> assertThat(deletedAt).isNotNull());
        assertThat(ledger(key)).containsEntry("status", "VERIFIED").containsEntry("object_deleted_at", null);

        // Once: the next run finds nothing, and the mark itself never changes again.
        kernel.reset();
        assertThat(cleanup.deleteFailedObjects()).isZero();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThatThrownBy(() -> superuserJdbc()
                        .update("update kernel.object_upload set object_deleted_at = now() where object_key = ?", key))
                .hasMessageContaining("object.immutable");
        assertThatThrownBy(() -> superuserJdbc()
                        .update(
                                "update kernel.object_upload set object_deleted_at = now() where object_key = ?",
                                wrongHash))
                .hasMessageContaining("object.immutable");
    }

    @Test
    void aSettledKeyGetsNoNewUrlAndTheRowNeverChangesAgain() {
        verified(key);

        refused(() -> inScope(OWNER, () -> presign(key)), "object.not_pending");

        assertThatThrownBy(() -> superuserJdbc()
                        .update(
                                "update kernel.object_upload set status = 'PENDING', settled_at = null where object_key = ?",
                                key))
                .hasMessageContaining("object.immutable");
        assertThatThrownBy(() -> superuserJdbc()
                        .update(
                                "update kernel.object_upload set upload_expires_at = now() + interval '1 hour'"
                                        + " where object_key = ?",
                                key))
                .hasMessageContaining("object.immutable");
    }

    @Test
    void onlyADerivedKeyOfAVerifiedObjectIsWrittenWithinTheLimits() {
        String thumb = key + "/thumb.png";
        byte[] png = {1, 2, 3};

        // Not presigned at all, then presigned but not verified.
        refused(() -> storage.write(thumb, "image/png", png, own(OWNER)), "object.not_found");
        inScope(OWNER, () -> presign(key));
        store.objects.put(key, bytes);
        refused(() -> storage.write(thumb, "image/png", png, own(OWNER)), "object.not_verified");
        refused(() -> storage.read(key, own(OWNER)), "object.not_verified");

        windowOver(key);
        storage.verify(key, own(OWNER));

        // The verified original itself is never written: its hash describes it.
        refused(() -> storage.write(key, "image/png", png, own(OWNER)), "object.not_derived");
        assertThat(store.objects.get(key)).isEqualTo(bytes);
        // The register's limits hold for what is derived too.
        refused(() -> storage.write(thumb, "image/png", new byte[6 * 1024 * 1024], own(OWNER)), "attachment.too_large");
        refused(() -> storage.write(thumb, "text/html", png, own(OWNER)), "attachment.content_type_not_allowed");
        assertThat(store.objects).doesNotContainKey(thumb);

        storage.write(thumb, "image/png", png, own(OWNER));
        assertThat(store.objects.get(thumb)).containsExactly(1, 2, 3);
        assertThat(storage.read(key, own(OWNER))).isEqualTo(bytes);
        assertThat(storage.read(thumb, own(OWNER))).containsExactly(1, 2, 3);
        assertThat(storage.presignGet(thumb, "image/png", own(OWNER)).toString())
                .isEqualTo("memory://get/" + thumb);
    }

    @Test
    void anotherEntitysScopeIsRefusedOnEveryMethod() {
        verified(key);
        String thumb = key + "/thumb.png";
        storage.write(thumb, "image/png", new byte[] {1}, own(OWNER));
        String pending = ObjectStorage.keyOf(ObjectStoragePostgresIntegrationTest.class, OWNER, Ids.next());
        inScope(OWNER, () -> presign(pending));
        kernel.reset();

        refused(
                () -> inScope(STRANGER, () -> storage.presignPut(pending, "image/png", null, null, own(STRANGER))),
                "object.scope_mismatch");
        refused(() -> storage.verify(pending, own(STRANGER)), "object.scope_mismatch");
        refused(
                () -> storage.write(key + "/other.png", "image/png", new byte[] {1}, own(STRANGER)),
                "object.scope_mismatch");
        refused(() -> storage.read(key, own(STRANGER)), "object.scope_mismatch");
        refused(() -> storage.read(thumb, own(STRANGER)), "object.scope_mismatch");
        // Uploading, verifying and deriving need the owner's OWN scope, not a view of it.
        refused(() -> storage.verify(pending, view()), "object.scope_mismatch");
        refused(() -> storage.write(key + "/other.png", "image/png", new byte[] {1}, view()), "object.scope_mismatch");
        // A read URL is the module's to give (it read the key under its own policies), so any
        // scope is admitted, but never none.
        ScopeContext none =
                new ScopeContext(USER, null, null, List.of(), null, PolicyClass.NONE, Set.of(), null, null, null);
        refused(() -> storage.presignGet(thumb, "image/png", none), "object.scope_mismatch");
        refused(() -> storage.presignGet(thumb, "image/png", null), "object.scope_mismatch");
        assertThat(storage.presignGet(thumb, "image/png", own(STRANGER))).isNotNull();

        // The federation view reads every entity's verified objects; the ledger's policies agree.
        assertThat(storage.read(key, view())).isEqualTo(bytes);
        assertThat(ledger(pending).get("status")).isEqualTo("PENDING");
        assertThat(store.objects).doesNotContainKey(key + "/other.png");
        assertThat(kernel.committedAudit()).isEmpty();
    }

    // ---- helpers -----------------------------------------------------------------------------

    private ObjectStorage.PresignedPut presign(String objectKey) {
        return storage.presignPut(
                objectKey, "image/png", (long) bytes.length, MemoryObjectStore.sha256(bytes), own(entityOf(objectKey)));
    }

    /** Presign, upload, let the window pass and verify: a VERIFIED object of the owner. */
    private void verified(String objectKey) {
        inScope(OWNER, () -> presign(objectKey));
        store.objects.put(objectKey, bytes);
        windowOver(objectKey);
        assertThat(storage.verify(objectKey, own(OWNER)).outcome()).isEqualTo(Outcome.VERIFIED);
    }

    private static UUID entityOf(String objectKey) {
        return UUID.fromString(objectKey.split("/")[2]);
    }

    private static void windowOver(String objectKey) {
        superuserJdbc()
                .update(
                        "update kernel.object_upload set upload_expires_at = now() - interval '1 minute'"
                                + " where object_key = ?",
                        objectKey);
    }

    private static Map<String, Object> ledger(String objectKey) {
        return superuserJdbc().queryForMap("select * from kernel.object_upload where object_key = ?", objectKey);
    }

    private List<KernelRecorder.AuditRecord> audit(String eventType) {
        return kernel.committedAudit().stream()
                .filter(record -> record.eventType().equals(eventType))
                .toList();
    }

    private static ScopeContext own(UUID entity) {
        Scope active = new Scope(entity, null);
        return new ScopeContext(
                USER, null, entity, List.of(active), active, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }

    private static ScopeContext view() {
        Scope active = new Scope(new UUID(0, 0), null);
        return new ScopeContext(
                USER,
                null,
                active.entityId(),
                List.of(active),
                active,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);
    }

    /** The handler's transaction with the entity's OWN scope applied, as the kernel's customizer does. */
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

    private static void refused(ThrowingCallable call, String messageId) {
        assertThatThrownBy(call).isInstanceOf(ProblemException.class).satisfies(error -> assertThat(
                        ((ProblemException) error).messageId())
                .isEqualTo(messageId));
    }
}
