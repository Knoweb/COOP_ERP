package lk.coopfed.knoweb.m2catalogue.internal.image;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.ImageRetired;
import lk.coopfed.knoweb.m2catalogue.api.RetireImage;
import lk.coopfed.knoweb.m2catalogue.internal.image.ImageStore.ImageRow;
import lk.coopfed.knoweb.m2catalogue.internal.sku.SkuAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RetireImage (22A section 5, {@code DELETE /skus/{skuId}/images/{imageId}}; doc 22 section 4:
 * "AttachImage / RetireImage ... cat.image.manage (owner)"). Guards: an entity-wide OWN scope;
 * the SKU visible; the image the caller's own, of that SKU; PENDING or ACTIVE. Mutation: status
 * RETIRED. Removing a local override brings the SKU owner's image back for the caller's tills.
 */
@Service
@CommandHandler(permission = "cat.image.manage")
public class RetireImageHandler implements Handles<RetireImage, UUID> {

    static final String AUDIT_RETIRED = "IMAGE_RETIRED";

    private final SkuAccess skus;
    private final ImageStore images;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    RetireImageHandler(SkuAccess skus, ImageStore images, JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.skus = skus;
        this.images = images;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RetireImage command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        if (command.imageId() == null) {
            throw new ProblemException("request.field.required", Map.of("field", "imageId"));
        }

        UUID skuId = skus.requireVisible(command.skuId(), scope).skuId();

        ImageRow before = images.ownForUpdate(command.imageId(), scope)
                .filter(row -> row.skuId().equals(skuId))
                .orElseThrow(() -> new ProblemException("m2.image.not_found", Map.of("imageId", command.imageId())));
        if (!ImageStore.PENDING.equals(before.status()) && !ImageStore.ACTIVE.equals(before.status())) {
            throw new ProblemException("m2.image.not_retirable", Map.of("status", before.status()));
        }

        jdbc.update("update catalogue.sku_image set status = 'RETIRED' where image_id = ?", before.imageId());

        ImageRow after = before.withStatus(ImageStore.RETIRED, before.objectKeyThumb());

        audit.record(AUDIT_RETIRED, Subject.of("sku", skuId), before.auditState(), after.auditState(), scope);

        events.publish(new ImageRetired(
                after.imageId(), skuId, after.barcode(), after.ownerEntityId(), after.objectKeyThumb()));

        return after.imageId();
    }
}
