package lk.coopfed.knoweb.m2catalogue.internal.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ObjectStorage;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.attachment.MemoryObjectStore;
import lk.coopfed.knoweb.m2catalogue.api.AttachImage;
import lk.coopfed.knoweb.m2catalogue.api.ImageAttached;
import lk.coopfed.knoweb.m2catalogue.api.ImageFailed;
import lk.coopfed.knoweb.m2catalogue.api.ImagePending;
import lk.coopfed.knoweb.m2catalogue.api.ImageRetired;
import lk.coopfed.knoweb.m2catalogue.api.ImageUpload;
import lk.coopfed.knoweb.m2catalogue.api.RetireImage;
import lk.coopfed.knoweb.m2catalogue.query.BarcodeLookup;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AttachImage, RetireImage and the thumbnail job that settles an image (22A section 6; M2-06):
 * every guard with its failing case and nothing committed, what each step audits and publishes,
 * the thumbnail produced and keyed, one ACTIVE image per key, the local override of a SHARED
 * item (DR-5) as the till's lookup sees it, and a settled row that never changes again. The
 * kernel's own {@code ObjectStorage} runs, with its upload ledger (CR-19A-7, as revised); only the
 * bytes are in memory.
 */
@Import(MemoryObjectStore.class)
class ImageHandlersPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION; // only its SHARED rows are published (m2catalogue V0006)
    private static final UUID MPCS_A = UUID.fromString("0190e680-0000-7000-8000-000000000002");
    private static final UUID MPCS_B = UUID.fromString("0190e680-0000-7000-8000-000000000003");
    private static final UUID USER = UUID.fromString("0190e680-0000-7000-8000-000000000010");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e680-0000-7000-8000-000000000100");

    private static final String EAN = "4791234567891";

    @Autowired
    AttachImageHandler attach;

    @Autowired
    RetireImageHandler retire;

    @Autowired
    SettleImageHandler settle;

    @Autowired
    ThumbnailJob job;

    @Autowired
    CatalogueQueries queries;

    @Autowired
    MemoryObjectStore storage;

    private final UUID sharedSku = Ids.next();
    private final UUID localSkuOfA = Ids.next();
    private final UUID localSkuOfB = Ids.next();
    private final UUID inactiveSkuOfA = Ids.next();

    private byte[] photo;
    private String photoHash;

    @BeforeEach
    void arrange() throws IOException {
        JdbcTemplate admin = superuserJdbc();
        clean();

        admin.update("insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'Each', false)"
                + " on conflict do nothing");
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)"
                        + " values (?, 'M2IMG', 'M2 image tax', ?) on conflict do nothing",
                TAX_CATEGORY,
                FEDERATION);

        insertSku(admin, sharedSku, "IM-SHARED", FEDERATION, "SHARED");
        insertSku(admin, localSkuOfA, "IM-LOCAL-A", MPCS_A, "LOCAL");
        insertSku(admin, localSkuOfB, "IM-LOCAL-B", MPCS_B, "LOCAL");
        insertSku(admin, inactiveSkuOfA, "IM-INACT-A", MPCS_A, "INACTIVE");
        admin.update(
                "insert into catalogue.sku_barcode (barcode, symbology, sku_id, uom_code, owner_entity_id, status)"
                        + " values (?, 'EAN13', ?, 'EA', ?, 'ACTIVE')",
                EAN,
                sharedSku,
                FEDERATION);

        storage.objects.clear();
        photo = image(640, 480);
        photoHash = MemoryObjectStore.sha256(photo);
        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table catalogue.batch, catalogue.sku cascade");
        admin.execute("delete from kernel.object_upload where owner_module = 'm2catalogue'");
        admin.update("delete from catalogue.tax_rate where tax_category_id = ?", TAX_CATEGORY);
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
    }

    // ---- AttachImage -------------------------------------------------------------------------

    @Test
    void theOwnerAttachesAPendingImageAndGetsAPresignedPut() {
        ImageUpload upload = attach.handle(
                new AttachImage(sharedSku, null, "image/JPEG", (long) photo.length, photoHash.toUpperCase()),
                own(FEDERATION));

        String key = ObjectStorage.keyOf(ImageHandlersPostgresIntegrationTest.class, FEDERATION, upload.imageId());
        assertThat(upload.uploadUrl().toString()).contains(key);
        assertThat(row(upload.imageId())).satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("object_key_full")).isEqualTo(key);
            assertThat(row.get("object_key_thumb")).isNull();
            assertThat(row.get("content_hash")).isEqualTo(photoHash);
            assertThat(row.get("content_type")).isEqualTo("image/jpeg");
            assertThat(row.get("owner_entity_id")).isEqualTo(FEDERATION);
        });

        assertThat(audit("IMAGE_ATTACHED")).singleElement().satisfies(record -> {
            assertThat(record.subject().type()).isEqualTo("sku");
            assertThat(record.subject().id()).isEqualTo(sharedSku);
            assertThat(((Map<?, ?>) record.after()).get("status")).isEqualTo("PENDING");
        });
        assertThat(events(ImagePending.class))
                .containsExactly(new ImagePending(upload.imageId(), sharedSku, null, FEDERATION));
    }

    @Test
    void theAttachGuardsRefuseBeforeAnythingIsWritten() {
        // A society cannot see another society's LOCAL item.
        refused(() -> attach.handle(jpeg(localSkuOfB, null), own(MPCS_A)), "m2.sku.not_found");
        // An inactive item takes no image.
        refused(() -> attach.handle(jpeg(inactiveSkuOfA, null), own(MPCS_A)), "m2.image.sku_inactive");
        // The barcode must be an ACTIVE code of the item.
        refused(() -> attach.handle(jpeg(sharedSku, "4790000000000"), own(FEDERATION)), "m2.image.barcode_unknown");
        // The register's m2.image.content_types: JPEG and PNG, which the thumbnail job reads.
        refused(
                () -> attach.handle(new AttachImage(sharedSku, null, "image/webp", null, photoHash), own(FEDERATION)),
                "m2.image.content_type_not_allowed");
        refused(
                () -> attach.handle(new AttachImage(sharedSku, null, null, null, photoHash), own(FEDERATION)),
                "m2.image.content_type_not_allowed");
        refused(
                () -> attach.handle(new AttachImage(sharedSku, null, "image/png", null, "abc"), own(FEDERATION)),
                "m2.image.hash_invalid");
        // The kernel's attachment.max_bytes, signed into the URL.
        refused(
                () -> attach.handle(
                        new AttachImage(sharedSku, null, "image/png", 6L * 1024 * 1024, photoHash), own(FEDERATION)),
                "attachment.too_large");
        // An entity-wide OWN scope.
        refused(
                () -> attach.handle(jpeg(sharedSku, null), ScopeContext.dev(USER, FEDERATION, Ids.next())),
                "scope.invalid");

        assertThat(count()).isZero();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void oneImageWaitsForItsUploadPerItemBarcodeAndOwner() {
        attach.handle(jpeg(sharedSku, null), own(FEDERATION));

        refused(() -> attach.handle(jpeg(sharedSku, null), own(FEDERATION)), "m2.image.pending_exists");

        // Another pack of the same item, and another entity's override, are other keys.
        attach.handle(jpeg(sharedSku, EAN), own(FEDERATION));
        attach.handle(jpeg(sharedSku, null), own(MPCS_A));
        assertThat(count()).isEqualTo(3);
    }

    // ---- the thumbnail job and SettleImage ---------------------------------------------------

    @Test
    void theJobVerifiesTheUploadMakesTheThumbnailAndShowsTheImage() throws IOException {
        ImageUpload upload = attach.handle(jpeg(sharedSku, null), own(FEDERATION));
        String key = ObjectStorage.keyOf(ImageHandlersPostgresIntegrationTest.class, FEDERATION, upload.imageId());
        storage.objects.put(key, photo);
        kernel.reset();

        // The pre-signed PUT is still valid: the bytes could still be replaced, nothing settles.
        assertThat(job.makeThumbnails(execution())).isZero();
        assertThat(row(upload.imageId()).get("status")).isEqualTo("PENDING");

        windowOver(upload.imageId());
        assertThat(job.makeThumbnails(execution())).isEqualTo(1);

        String thumbKey = key + "/thumb.jpg";
        assertThat(row(upload.imageId())).satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("ACTIVE");
            assertThat(row.get("object_key_thumb")).isEqualTo(thumbKey);
        });
        // The thumbnail is produced and keyed: a JPEG of at most 128 pixels a side (CR-22A-2).
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(storage.objects.get(thumbKey)));
        assertThat(thumb.getWidth()).isEqualTo(128);
        assertThat(thumb.getHeight()).isEqualTo(96);

        assertThat(audit("IMAGE_ACTIVATED")).singleElement().satisfies(record -> {
            assertThat(record.subject().id()).isEqualTo(sharedSku);
            assertThat(((Map<?, ?>) record.before()).get("status")).isEqualTo("PENDING");
            assertThat(((Map<?, ?>) record.after()).get("status")).isEqualTo("ACTIVE");
        });
        assertThat(events(ImageAttached.class))
                .containsExactly(new ImageAttached(upload.imageId(), sharedSku, null, FEDERATION, thumbKey, null));

        // The till's lookup carries the thumb key (22A section 5, LookupResult.thumbKey).
        assertThat(lookup(EAN, MPCS_A)).contains(thumbKey);

        // A second run finds nothing to do.
        kernel.reset();
        assertThat(job.makeThumbnails(execution())).isZero();
        assertThat(kernel.committedAudit()).isEmpty();
    }

    @Test
    void aNewImageReplacesTheOwnersActiveOneWhenItIsReady() {
        UUID first = activeImage(sharedSku, FEDERATION);
        kernel.reset();

        UUID second = activeImage(sharedSku, FEDERATION);

        assertThat(row(first).get("status")).isEqualTo("RETIRED");
        assertThat(row(second).get("status")).isEqualTo("ACTIVE");
        assertThat(events(ImageAttached.class)).singleElement().satisfies(event -> assertThat(event.replacedImageId())
                .isEqualTo(first));
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from catalogue.sku_image where sku_id = ? and status = 'ACTIVE'",
                                Integer.class,
                                sharedSku))
                .isEqualTo(1);
    }

    @Test
    void aLocalOverrideIsShownOnTheOverridingEntitysTillsOnly() {
        UUID federationImage = activeImage(sharedSku, FEDERATION);
        UUID override = activeImage(sharedSku, MPCS_A);

        String federationThumb = (String) row(federationImage).get("object_key_thumb");
        String overrideThumb = (String) row(override).get("object_key_thumb");

        // Both are ACTIVE: the owner is in the key of one_active_image.
        assertThat(row(federationImage).get("status")).isEqualTo("ACTIVE");
        assertThat(lookup(EAN, MPCS_A)).contains(overrideThumb);
        assertThat(lookup(EAN, MPCS_B)).contains(federationThumb);
        assertThat(lookup(EAN, FEDERATION)).contains(federationThumb);

        // Retiring the override brings the Federation's image back for A.
        kernel.reset();
        retire.handle(new RetireImage(sharedSku, override), own(MPCS_A));
        assertThat(lookup(EAN, MPCS_A)).contains(federationThumb);
        assertThat(audit("IMAGE_RETIRED")).hasSize(1);
        assertThat(events(ImageRetired.class))
                .containsExactly(new ImageRetired(override, sharedSku, null, MPCS_A, overrideThumb));
    }

    @Test
    void anUploadWithAnotherHashOrNotAnImageFails() {
        ImageUpload wrongHash = attach.handle(jpeg(sharedSku, null), own(FEDERATION));
        storage.objects.put(key(FEDERATION, wrongHash), "other bytes".getBytes(StandardCharsets.UTF_8));
        windowOver(wrongHash.imageId());

        byte[] pdf = "%PDF-1.7 not a picture".getBytes(StandardCharsets.US_ASCII);
        ImageUpload notAnImage = attach.handle(
                new AttachImage(localSkuOfA, null, "image/png", null, MemoryObjectStore.sha256(pdf)), own(MPCS_A));
        storage.objects.put(key(MPCS_A, notAnImage), pdf);
        windowOver(notAnImage.imageId());
        kernel.reset();

        assertThat(job.makeThumbnails(execution())).isEqualTo(2);

        assertThat(row(wrongHash.imageId()).get("status")).isEqualTo("FAILED");
        assertThat(row(notAnImage.imageId()).get("status")).isEqualTo("FAILED");
        assertThat(audit("IMAGE_FAILED")).hasSize(2).anySatisfy(record -> assertThat(record.reason())
                .contains("hash mismatch"));
        assertThat(events(ImageFailed.class))
                .extracting(ImageFailed::imageId)
                .containsExactlyInAnyOrder(wrongHash.imageId(), notAnImage.imageId());
        assertThat(events(ImageAttached.class)).isEmpty();
    }

    @Test
    void anUploadThatNeverCameFailsAfterTheUploadWindow() {
        ImageUpload fresh = attach.handle(jpeg(sharedSku, null), own(FEDERATION));
        ImageUpload old = attach.handle(jpeg(localSkuOfA, null), own(MPCS_A));
        windowOver(fresh.imageId());
        windowOver(old.imageId());
        superuserJdbc()
                .update(
                        "update catalogue.sku_image set created_at = now() - interval '25 hours' where image_id = ?",
                        old.imageId());
        kernel.reset();

        assertThat(job.makeThumbnails(execution())).isEqualTo(1);

        assertThat(row(fresh.imageId()).get("status")).isEqualTo("PENDING");
        assertThat(row(old.imageId()).get("status")).isEqualTo("FAILED");
        assertThat(audit("IMAGE_FAILED")).singleElement().satisfies(record -> assertThat(record.reason())
                .contains("not uploaded within"));
    }

    @Test
    void theSettleGuardsRefuseAnOpenWindowASettledImageAndAnotherEntitysImage() {
        ImageUpload upload = attach.handle(jpeg(sharedSku, null), own(FEDERATION));
        kernel.reset();

        refused(
                () -> settle.handle(SettleImage.activate(upload.imageId(), "k/thumb.png"), own(FEDERATION)),
                "m2.image.upload_open");
        windowOver(upload.imageId());
        refused(
                () -> settle.handle(SettleImage.activate(upload.imageId(), "k/thumb.png"), own(MPCS_A)),
                "m2.image.not_found");
        refused(() -> settle.handle(new SettleImage(upload.imageId(), null, null), own(FEDERATION)), "request.invalid");
        assertThat(kernel.committedAudit()).isEmpty();

        settle.handle(SettleImage.fail(upload.imageId(), "test"), own(FEDERATION));
        refused(
                () -> settle.handle(SettleImage.activate(upload.imageId(), "k/thumb.png"), own(FEDERATION)),
                "m2.image.not_pending");
        assertThat(audit("IMAGE_FAILED")).hasSize(1);
    }

    // ---- RetireImage -------------------------------------------------------------------------

    @Test
    void theRetireGuardsRefuseAnotherEntitysImageAndASettledOne() {
        UUID federationImage = activeImage(sharedSku, FEDERATION);
        kernel.reset();

        // A society retires nothing of the Federation's.
        refused(() -> retire.handle(new RetireImage(sharedSku, federationImage), own(MPCS_A)), "m2.image.not_found");
        // Nor an image named under another item.
        refused(() -> retire.handle(new RetireImage(localSkuOfA, federationImage), own(MPCS_A)), "m2.image.not_found");
        assertThat(kernel.committedAudit()).isEmpty();

        retire.handle(new RetireImage(sharedSku, federationImage), own(FEDERATION));
        refused(
                () -> retire.handle(new RetireImage(sharedSku, federationImage), own(FEDERATION)),
                "m2.image.not_retirable");
        assertThat(audit("IMAGE_RETIRED")).hasSize(1);
        assertThat(lookup(EAN, MPCS_A)).isEmpty();
    }

    @Test
    void aSettledImageNeverChangesAgain() {
        UUID image = activeImage(localSkuOfA, MPCS_A);
        retire.handle(new RetireImage(localSkuOfA, image), own(MPCS_A));

        assertThatThrownBy(() -> superuserJdbc()
                        .update("update catalogue.sku_image set status = 'ACTIVE' where image_id = ?", image))
                .hasMessageContaining("m2.image.immutable");
        assertThatThrownBy(() -> superuserJdbc()
                        .update("update catalogue.sku_image set object_key_thumb = 'x' where image_id = ?", image))
                .hasMessageContaining("m2.image.immutable");
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** Attach, upload, let the window pass and run the job: an ACTIVE image of that owner. */
    private UUID activeImage(UUID skuId, UUID owner) {
        ImageUpload upload = attach.handle(jpeg(skuId, null), own(owner));
        storage.objects.put(key(owner, upload), photo);
        windowOver(upload.imageId());
        assertThat(job.makeThumbnails(execution())).isEqualTo(1);
        return upload.imageId();
    }

    private AttachImage jpeg(UUID skuId, String barcode) {
        return new AttachImage(skuId, barcode, "image/jpeg", (long) photo.length, photoHash);
    }

    private static String key(UUID owner, ImageUpload upload) {
        return ObjectStorage.keyOf(ImageHandlersPostgresIntegrationTest.class, owner, upload.imageId());
    }

    /** The PUT URL expired, as M2's row and the kernel's ledger both record it. */
    private static void windowOver(UUID imageId) {
        superuserJdbc()
                .update(
                        "update catalogue.sku_image set upload_expires_at = now() - interval '1 minute' where image_id = ?",
                        imageId);
        superuserJdbc()
                .update(
                        "update kernel.object_upload set upload_expires_at = now() - interval '1 minute'"
                                + " where object_key = (select object_key_full from catalogue.sku_image where image_id = ?)",
                        imageId);
    }

    private Optional<String> lookup(String barcode, UUID entity) {
        return queries.lookupByBarcode(new BarcodeLookup(barcode, null, null, null, null, null), own(entity))
                .map(result -> result.thumbKey());
    }

    private static JobExecution execution() {
        UUID runId = Ids.next();
        return new JobExecution() {
            @Override
            public UUID runId() {
                return runId;
            }

            @Override
            public void itemsProcessed(int count) {}

            @Override
            public Optional<ScopeContext> systemScope() {
                return Optional.of(ScopeContext.dev(null, TEST_FEDERATION, null));
            }
        };
    }

    private static void insertSku(JdbcTemplate admin, UUID skuId, String code, UUID owner, String status) {
        admin.update(
                """
                insert into catalogue.sku
                    (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si, short_name_ta,
                     base_uom_code, tax_category_id)
                values (?, ?, ?, ?, ?, ?, ?, 'EA', ?)
                """,
                skuId,
                code,
                owner,
                status,
                code,
                code + " si",
                code + " ta",
                TAX_CATEGORY);
    }

    private static Map<String, Object> row(UUID imageId) {
        return superuserJdbc().queryForMap("select * from catalogue.sku_image where image_id = ?", imageId);
    }

    private static int count() {
        return superuserJdbc().queryForObject("select count(*) from catalogue.sku_image", Integer.class);
    }

    private static ScopeContext own(UUID entity) {
        return ScopeContext.dev(USER, entity, null);
    }

    private static void refused(ThrowingCallable call, String messageId) {
        assertThatThrownBy(call).isInstanceOf(ProblemException.class).satisfies(error -> assertThat(
                        ((ProblemException) error).messageId())
                .isEqualTo(messageId));
    }

    private List<KernelRecorder.AuditRecord> audit(String eventType) {
        return kernel.committedAudit().stream()
                .filter(record -> record.eventType().equals(eventType))
                .toList();
    }

    private <E extends DomainEvent> List<E> events(Class<E> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private static byte[] image(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.RED);
        g.fillRect(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
