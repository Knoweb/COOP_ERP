package lk.coopfed.knoweb.m2catalogue.internal.image;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ObjectStorage;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.internal.image.ImageStore.ImageRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The ThumbnailJob of 22A section 4 (doc 22 section 3.4, "ImageThumbnailJob"), registered with
 * the kernel's job runner. It is also the verifier hook of 22A section 6 ("on upload event:
 * verify hash, ThumbnailJob, ACTIVE"): an image's owner is a SKU, not a document, so the kernel's
 * attachment verifier does not see it (CR-19A-7), and this job asks the kernel's
 * {@link ObjectStorage} the verifier's questions itself.
 *
 * <p>Every run: the PENDING images whose pre-signed PUT has expired (read with a federation-wide
 * view, at most {@code m2.image.thumbnail.batch_size}), oldest first. For each, in the OWN scope
 * of the entity that attached it:
 * <ul>
 *   <li>nothing uploaded: left PENDING until {@code m2.image.upload_window_hours} after it was
 *       attached, then FAILED;
 *   <li>larger than {@code attachment.max_bytes}, or another hash than announced: FAILED;
 *   <li>not an image the JDK reads, or more than {@code m2.image.max_pixels}: FAILED;
 *   <li>otherwise a PNG thumbnail of at most {@code m2.image.thumbnail_px} pixels is written
 *       beside the full image ({@code <full key>/thumb.png}) and the image becomes ACTIVE.
 * </ul>
 * The store is asked outside any transaction; each image is settled by {@link SettleImageHandler}
 * in a transaction of its own, so one bad upload does not hold the rest, and a row another run or
 * a retirement settled first is skipped.
 */
@Component
class ThumbnailJob {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailJob.class);

    static final String THUMB_SUFFIX = "/thumb.png";

    /** The handler's answers that mean somebody else settled the image first: not a failure. */
    static final Set<String> SKIPPED = Set.of("m2.image.not_pending", "m2.image.not_found", "m2.image.upload_open");

    private final ImageStore images;
    private final ImageSettings settings;
    private final SettleImageHandler settle;
    private final ObjectStorage storage;
    private final Clock clock;

    ThumbnailJob(
            ImageStore images, ImageSettings settings, SettleImageHandler settle, ObjectStorage storage, Clock clock) {
        this.images = images;
        this.settings = settings;
        this.settle = settle;
        this.storage = storage;
        this.clock = clock;
    }

    @ScheduledJob(
            name = "image-thumbnail",
            cron = "${coop-erp.m2.image-thumbnail-cron:30 * * * * *}",
            lockTimeout = "PT15M",
            maxRuntime = "PT10M")
    public int makeThumbnails(JobExecution execution) {
        ScopeContext view = execution.federationView();
        List<ImageRow> due = images.dueForThumbnail(
                view, clock.instant(), settings.limits(view).batchSize());

        int settled = 0;
        for (ImageRow row : due) {
            try {
                if (process(row, execution.ownScopeOf(row.ownerEntityId()))) {
                    settled++;
                }
            } catch (ProblemException overtaken) {
                if (SKIPPED.contains(overtaken.messageId())) {
                    log.debug("Image {} skipped: {}", row.imageId(), overtaken.messageId());
                } else {
                    log.error("Image {} could not be settled", row.imageId(), overtaken);
                }
            } catch (RuntimeException e) {
                log.error("Image {} could not be settled", row.imageId(), e);
            }
        }
        return settled;
    }

    /** One image; true when it was settled (ACTIVE or FAILED), false when it stays PENDING. */
    boolean process(ImageRow row, ScopeContext owner) {
        ImageSettings.Limits limits = settings.limits(owner);
        ObjectStorage.Verification verification = storage.verify(row.objectKeyFull(), row.contentHash(), owner);

        switch (verification.outcome()) {
            case MISSING -> {
                if (row.createdAt().plus(limits.uploadWindow()).isAfter(clock.instant())) {
                    return false;
                }
                settle.handle(SettleImage.fail(row.imageId(), "not uploaded within " + limits.uploadWindow()), owner);
                return true;
            }
            case TOO_LARGE -> {
                settle.handle(SettleImage.fail(row.imageId(), "too large: " + verification.size() + " bytes"), owner);
                return true;
            }
            case HASH_MISMATCH -> {
                settle.handle(
                        SettleImage.fail(
                                row.imageId(),
                                "hash mismatch: announced " + row.contentHash() + ", stored "
                                        + verification.sha256Hex()),
                        owner);
                return true;
            }
            case VERIFIED -> {
                byte[] thumbnail;
                try {
                    thumbnail = Thumbnailer.thumbnail(
                            storage.read(row.objectKeyFull(), owner), limits.thumbnailPx(), limits.maxPixels());
                } catch (Thumbnailer.NotAnImage notAnImage) {
                    settle.handle(SettleImage.fail(row.imageId(), notAnImage.getMessage()), owner);
                    return true;
                }
                String thumbKey = row.objectKeyFull() + THUMB_SUFFIX;
                storage.write(thumbKey, Thumbnailer.CONTENT_TYPE, thumbnail);
                settle.handle(SettleImage.activate(row.imageId(), thumbKey), owner);
                return true;
            }
            default -> throw new IllegalStateException("Unknown outcome " + verification.outcome());
        }
    }
}
