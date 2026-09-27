package lk.coopfed.knoweb.m2catalogue.internal.image;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.ImageAttached;
import lk.coopfed.knoweb.m2catalogue.api.ImageFailed;
import lk.coopfed.knoweb.m2catalogue.internal.image.ImageStore.ImageRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The second half of AttachImage (22A section 6: "on upload event: verify hash, ThumbnailJob,
 * ACTIVE"), sent by {@link ThumbnailJob} in the OWN scope of the image's owner. Guards: an OWN
 * scope; the caller's own image, still PENDING ({@code m2.image.not_pending}: a retirement or
 * another run got there first); its pre-signed PUT expired ({@code m2.image.upload_open}: until
 * then the verified bytes could still be replaced, the K-09 rule; the kernel's upload ledger
 * enforces it too since CR-19A-7 was revised, and this guard stays for a settle that does not
 * come through the kernel's verification). Mutation, when verified: the
 * caller's earlier ACTIVE image of the same SKU and barcode RETIRED, this one ACTIVE with its
 * thumbnail (one ACTIVE per key); when not: FAILED.
 *
 * <p>The job runs with no user, so the permission is not checked (as for M1's grant expiry);
 * it is the owner's {@code cat.image.manage} because the job finishes the owner's command.
 */
@Service
@CommandHandler(permission = "cat.image.manage")
class SettleImageHandler implements Handles<SettleImage, UUID> {

    static final String AUDIT_ACTIVATED = "IMAGE_ACTIVATED";
    static final String AUDIT_FAILED = "IMAGE_FAILED";

    private final ImageStore images;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    SettleImageHandler(ImageStore images, JdbcTemplate jdbc, Clock clock, AuditFacade audit, EventPublisher events) {
        this.images = images;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(SettleImage command, ScopeContext scope) {
        if (command == null || command.imageId() == null) {
            throw new ProblemException("request.invalid");
        }
        boolean activate = command.thumbKey() != null && !command.thumbKey().isBlank();
        boolean fail = command.failure() != null && !command.failure().isBlank();
        if (activate == fail) {
            throw new ProblemException("request.invalid");
        }
        if (scope == null
                || !scope.hasActiveScope()
                || scope.entityId() == null
                || scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("scope.invalid");
        }

        ImageRow before = images.ownForUpdate(command.imageId(), scope)
                .orElseThrow(() -> new ProblemException("m2.image.not_found", Map.of("imageId", command.imageId())));
        if (!ImageStore.PENDING.equals(before.status())) {
            throw new ProblemException("m2.image.not_pending", Map.of("status", before.status()));
        }
        if (clock.instant().isBefore(before.uploadExpiresAt())) {
            throw new ProblemException("m2.image.upload_open");
        }

        if (fail) {
            jdbc.update("update catalogue.sku_image set status = 'FAILED' where image_id = ?", before.imageId());
            ImageRow after = before.withStatus(ImageStore.FAILED, null);
            audit.record(
                    AUDIT_FAILED,
                    Subject.of("sku", before.skuId()),
                    before.auditState(),
                    after.auditState(),
                    scope,
                    command.failure(),
                    null);
            events.publish(
                    new ImageFailed(before.imageId(), before.skuId(), before.ownerEntityId(), command.failure()));
            return before.imageId();
        }

        // One ACTIVE image per (sku, barcode, owner): the one it replaces is retired first, in
        // the same transaction, so the unique index one_active_image never sees two.
        Optional<ImageRow> replaced =
                images.ownWithStatus(before.skuId(), before.barcode(), ImageStore.ACTIVE, before.imageId(), scope);
        if (replaced.isPresent()) {
            jdbc.update(
                    "update catalogue.sku_image set status = 'RETIRED' where image_id = ?",
                    replaced.get().imageId());
        }

        jdbc.update(
                "update catalogue.sku_image set status = 'ACTIVE', object_key_thumb = ? where image_id = ?",
                command.thumbKey(),
                before.imageId());
        ImageRow after = before.withStatus(ImageStore.ACTIVE, command.thumbKey());

        Map<String, Object> state = new LinkedHashMap<>(after.auditState());
        state.put("replacedImageId", replaced.map(ImageRow::imageId).orElse(null));
        audit.record(AUDIT_ACTIVATED, Subject.of("sku", before.skuId()), before.auditState(), state, scope);

        events.publish(new ImageAttached(
                after.imageId(),
                after.skuId(),
                after.barcode(),
                after.ownerEntityId(),
                after.objectKeyThumb(),
                replaced.map(ImageRow::imageId).orElse(null)));

        return after.imageId();
    }
}
