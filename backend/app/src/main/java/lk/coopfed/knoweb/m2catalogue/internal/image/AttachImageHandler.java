package lk.coopfed.knoweb.m2catalogue.internal.image;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ObjectStorage;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.AttachImage;
import lk.coopfed.knoweb.m2catalogue.api.ImagePending;
import lk.coopfed.knoweb.m2catalogue.api.ImageUpload;
import lk.coopfed.knoweb.m2catalogue.internal.image.ImageStore.ImageRow;
import lk.coopfed.knoweb.m2catalogue.internal.sku.SkuAccess;
import lk.coopfed.knoweb.m2catalogue.internal.sku.SkuIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AttachImage (22A section 6; doc 22 section 3.4). Guards, in order: an entity-wide OWN scope;
 * the SKU visible and not INACTIVE; the caller owns it, or it is SHARED and this is the caller's
 * local override (DR-5, allowed); the barcode, when given, an ACTIVE code of the SKU the caller
 * can see; a type of the register's {@code m2.image.content_types}; a SHA-256 of 64 hex digits;
 * no other image of the caller for the same SKU and barcode still waiting for its upload.
 * Mutation: a PENDING row whose object key is the kernel's ({@link ObjectStorage#keyOf}) and a
 * pre-signed PUT under the register's size and type limits (CR-19A-7).
 *
 * <p>"One active per key" (22A section 6) is kept when the image becomes ACTIVE: SettleImage
 * retires the caller's earlier ACTIVE image of the same key in the same transaction, so a till
 * keeps showing the old picture until the new one is ready.
 */
@Service
@CommandHandler(permission = "cat.image.manage")
public class AttachImageHandler implements Handles<AttachImage, ImageUpload> {

    static final String AUDIT_ATTACHED = "IMAGE_ATTACHED";
    static final String CONTENT_TYPES = "m2.image.content_types";
    static final String MODULE = "m2catalogue";

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private final SkuAccess skus;
    private final ImageStore images;
    private final ObjectStorage storage;
    private final ConfigRegistry config;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    AttachImageHandler(
            SkuAccess skus,
            ImageStore images,
            ObjectStorage storage,
            ConfigRegistry config,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events) {
        this.skus = skus;
        this.images = images;
        this.storage = storage;
        this.config = config;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public ImageUpload handle(AttachImage command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        SkuIdentity sku = skus.requireVisible(command.skuId(), scope);
        if ("INACTIVE".equals(sku.status())) {
            throw new ProblemException("m2.image.sku_inactive", Map.of("skuId", sku.skuId()));
        }
        boolean owner = scope.entityId().equals(sku.ownerEntityId());
        if (!owner && !"SHARED".equals(sku.status())) {
            throw new ProblemException("m2.sku.owner_mismatch", Map.of("skuId", sku.skuId()));
        }

        String barcode = command.barcode() == null || command.barcode().isBlank()
                ? null
                : command.barcode().strip();
        if (barcode != null) {
            Integer active = jdbc.queryForObject(
                    "select count(*) from catalogue.sku_barcode where sku_id = ? and barcode = ? and status = 'ACTIVE'",
                    Integer.class,
                    sku.skuId(),
                    barcode);
            if (active == null || active == 0) {
                throw new ProblemException("m2.image.barcode_unknown", Map.of("barcode", barcode));
            }
        }

        String contentType = command.contentType() == null
                ? null
                : command.contentType().strip().toLowerCase();
        if (contentType == null || !allowedTypes(scope).contains(contentType)) {
            throw new ProblemException(
                    "m2.image.content_type_not_allowed", Map.of("contentType", String.valueOf(contentType)));
        }

        String hash =
                command.sha256Hex() == null ? null : command.sha256Hex().strip().toLowerCase();
        if (hash == null || !SHA256_HEX.matcher(hash).matches()) {
            throw new ProblemException("m2.image.hash_invalid");
        }

        if (images.ownWithStatus(sku.skuId(), barcode, ImageStore.PENDING, null, scope)
                .isPresent()) {
            throw new ProblemException("m2.image.pending_exists", Map.of("skuId", sku.skuId()));
        }

        UUID imageId = Ids.next();
        String key = ObjectStorage.keyOf(MODULE, scope.entityId(), imageId);
        // The kernel holds the size and the type to the register's attachment limits and signs
        // them into the URL; nothing is written before it has answered.
        ObjectStorage.PresignedPut put = storage.presignPut(key, contentType, command.contentLength(), scope);

        jdbc.update(
                """
                insert into catalogue.sku_image
                    (image_id, sku_id, barcode, owner_entity_id, object_key_full, content_hash, content_type,
                     status, upload_expires_at)
                values (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """,
                imageId,
                sku.skuId(),
                barcode,
                scope.entityId(),
                key,
                hash,
                contentType,
                Timestamp.from(put.expiresAt()));

        ImageRow row = new ImageRow(
                imageId,
                sku.skuId(),
                barcode,
                scope.entityId(),
                key,
                null,
                hash,
                contentType,
                ImageStore.PENDING,
                put.expiresAt(),
                null);

        audit.record(AUDIT_ATTACHED, Subject.of("sku", sku.skuId()), null, row.auditState(), scope);

        events.publish(new ImagePending(imageId, sku.skuId(), barcode, scope.entityId()));

        return new ImageUpload(imageId, put.url(), put.expiresAt());
    }

    private Set<String> allowedTypes(ScopeContext scope) {
        String list = config.getOrDefault(CONTENT_TYPES, scope, "image/jpeg,image/png");
        return Arrays.stream(list.split(",")).map(String::strip).collect(Collectors.toSet());
    }
}
