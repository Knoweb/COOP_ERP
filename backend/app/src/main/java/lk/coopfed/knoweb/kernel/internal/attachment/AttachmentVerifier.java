package lk.coopfed.knoweb.kernel.internal.attachment;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The verifier of 19A section 9: every five minutes, for each PENDING attachment, ask the
 * store whether the object is there; when it is, hash it and compare with what the client
 * announced (or take the store's hash when nothing was announced), then COMPLETE or FAILED.
 * An object that has not arrived inside the upload window is FAILED too, so a decision
 * waiting on evidence is not left waiting for ever.
 *
 * <p>Reads as a federation-wide viewer; writes each row in the OWN scope of the document's
 * entity, one transaction per row, so one bad object does not hold the rest.
 */
@Component
public class AttachmentVerifier {

    private static final Logger log = LoggerFactory.getLogger(AttachmentVerifier.class);

    private final AttachmentService attachments;
    private final ObjectStore store;
    private final SystemScope system;
    private final Clock clock;
    private final Duration uploadWindow;

    AttachmentVerifier(
            AttachmentService attachments,
            ObjectStore store,
            SystemScope system,
            Clock clock,
            @Value("${coop-erp.object-store.upload-window-hours}") int uploadWindowHours) {
        this.attachments = attachments;
        this.store = store;
        this.system = system;
        this.clock = clock;
        this.uploadWindow = Duration.ofHours(uploadWindowHours);
    }

    @ScheduledJob(name = "attachment-verify", cron = "0 */5 * * * *", lockTimeout = "PT15M", maxRuntime = "PT10M")
    public int verifyPending() {
        List<AttachmentService.Pending> pending = system.inScope(SystemScope.federationView(), attachments::pending);

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
                system.inScope(owner, () -> {
                    attachments.fail(row, "not uploaded within " + uploadWindow, owner);
                    return null;
                });
                return true;
            }
            return false;
        }

        String hash = store.sha256Hex(row.objectKey());

        if (row.expectedHash() != null && !row.expectedHash().equalsIgnoreCase(hash)) {
            system.inScope(owner, () -> {
                attachments.fail(row, "hash mismatch: announced " + row.expectedHash() + ", stored " + hash, owner);
                return null;
            });
            return true;
        }

        system.inScope(owner, () -> {
            attachments.complete(row, hash, owner);
            return null;
        });
        return true;
    }
}
