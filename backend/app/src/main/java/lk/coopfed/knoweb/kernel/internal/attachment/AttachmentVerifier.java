package lk.coopfed.knoweb.kernel.internal.attachment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The verifier of 19A section 9: every five minutes, for each PENDING attachment whose upload
 * window has ended, ask the store whether the object is there; when it is, check its size,
 * hash it and compare with what the client announced (or take the store's hash when nothing
 * was announced), then COMPLETE or FAILED. An object that has not arrived inside the upload
 * window is FAILED too, so a decision waiting on evidence is not left waiting for ever.
 *
 * <p>A row is settled only after its pre-signed PUT has expired (review of 26 September):
 * until then the same URL could replace the bytes after the hash was checked, and the
 * content hash and the approval that read it would no longer describe the stored object.
 * Once settled, the row never changes (kernel V0057), and no new URL is issued for it.
 *
 * <p>Reads as a federation-wide viewer, at most {@code attachment.verify.batch_size} rows per
 * run; writes each row in the OWN scope of the document's entity, one transaction per row
 * and conditional on the row still being PENDING, so one bad object does not hold the rest
 * and a run that outlives its lock settles nothing twice.
 */
@Component
public class AttachmentVerifier {

    private static final Logger log = LoggerFactory.getLogger(AttachmentVerifier.class);

    private final AttachmentService attachments;
    private final ObjectStore store;
    private final SystemScope system;
    private final ConfigRegistry config;
    private final Clock clock;
    private final Duration uploadWindow;

    AttachmentVerifier(
            AttachmentService attachments,
            ObjectStore store,
            SystemScope system,
            ConfigRegistry config,
            Clock clock,
            @Value("${coop-erp.object-store.upload-window-hours}") int uploadWindowHours) {
        this.attachments = attachments;
        this.store = store;
        this.system = system;
        this.config = config;
        this.clock = clock;
        this.uploadWindow = Duration.ofHours(uploadWindowHours);
    }

    @ScheduledJob(name = "attachment-verify", cron = "0 */5 * * * *", lockTimeout = "PT30M", maxRuntime = "PT10M")
    public int verifyPending() {
        ScopeContext viewer = SystemScope.federationView();
        Instant now = clock.instant();
        List<AttachmentService.Pending> pending = system.inScope(
                viewer,
                () -> attachments.pending(now, config.getInt(AttachmentService.VERIFY_BATCH_SIZE, viewer, 500)));

        int settled = 0;

        for (AttachmentService.Pending row : pending) {
            try {
                if (verify(row)) {
                    settled++;
                }
            } catch (RuntimeException e) {
                log.error("Could not verify attachment {}", row.attachmentId(), e);
            }
        }

        return settled;
    }

    /** One row; true when it was settled (COMPLETE or FAILED), false when it stays PENDING. */
    boolean verify(AttachmentService.Pending row) {
        ScopeContext owner = SystemScope.own(row.ownerEntityId(), null);
        Optional<Long> size = store.head(row.objectKey());

        if (size.isEmpty()) {
            if (row.capturedAt().plus(uploadWindow).isBefore(clock.instant())) {
                return system.inScope(owner, () -> attachments.fail(row, "not uploaded within " + uploadWindow, owner));
            }
            return false;
        }

        long maxBytes = system.inScope(owner, () -> attachments.maxBytes(owner));
        if (size.get() > maxBytes) {
            // Refused before it is read: hashing an object of any size would starve the run.
            return system.inScope(
                    owner,
                    () -> attachments.fail(row, "too large: " + size.get() + " bytes, at most " + maxBytes, owner));
        }

        String hash = store.sha256Hex(row.objectKey());

        if (row.expectedHash() != null && !row.expectedHash().equalsIgnoreCase(hash)) {
            return system.inScope(
                    owner,
                    () -> attachments.fail(
                            row, "hash mismatch: announced " + row.expectedHash() + ", stored " + hash, owner));
        }

        return system.inScope(owner, () -> attachments.complete(row, hash, owner));
    }
}
