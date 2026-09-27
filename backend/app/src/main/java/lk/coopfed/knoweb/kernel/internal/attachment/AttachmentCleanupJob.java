package lk.coopfed.knoweb.kernel.internal.attachment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deletes the object of an upload that failed verification, once it has been kept for the
 * register's {@code attachment.failed_retention_days} (decided 27 September 2026 on the
 * architect's delegation, CR-19A-8). Both kinds: a document's attachment
 * ({@code kernel.document_attachment}) and a module's object ({@code kernel.object_upload}).
 *
 * <p>Why a retention and not at once: a FAILED upload is a finding (a hash mismatch, an object
 * over the limit), and someone may want to look at the bytes while the till's operator is asked
 * about it. Why at all: nobody can read a FAILED object (a read URL is given for a COMPLETE or
 * VERIFIED one only), so after that it is storage paid for nothing.
 *
 * <p>What is never deleted here: a COMPLETE attachment (evidence of a document) and a VERIFIED
 * module object (the module decides when it is no longer referenced; a module-side sweep is a
 * follow-up ticket, docs/PLAN_TO_M2.md). The row stays in both tables; it gains
 * {@code object_deleted_at}, the one change a settled row admits (kernel V0063).
 *
 * <p>The object is deleted first and the row marked after, in the owner's scope, one row per
 * transaction: a run that stops between the two deletes the missing object again next time,
 * which the store answers with success. Nightly by default; the schedule is
 * {@code coop-erp.attachment.cleanup-cron}.
 */
@Component
public class AttachmentCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AttachmentCleanupJob.class);

    private final AttachmentService attachments;
    private final ObjectStorageService objects;
    private final ObjectStore store;
    private final SystemScope system;
    private final ConfigRegistry config;
    private final Clock clock;

    AttachmentCleanupJob(
            AttachmentService attachments,
            ObjectStorageService objects,
            ObjectStore store,
            SystemScope system,
            ConfigRegistry config,
            Clock clock) {
        this.attachments = attachments;
        this.objects = objects;
        this.store = store;
        this.system = system;
        this.config = config;
        this.clock = clock;
    }

    @ScheduledJob(
            name = "attachment-cleanup",
            cron = "${coop-erp.attachment.cleanup-cron:0 40 3 * * *}",
            lockTimeout = "PT1H",
            maxRuntime = "PT30M")
    public int deleteFailedObjects() {
        ScopeContext viewer = SystemScope.federationView();
        int retentionDays =
                system.inScope(viewer, () -> config.getInt(AttachmentService.FAILED_RETENTION_DAYS, viewer, 30));
        int limit = system.inScope(viewer, () -> config.getInt(AttachmentService.CLEANUP_BATCH_SIZE, viewer, 500));
        Instant settledBefore = clock.instant().minus(Duration.ofDays(retentionDays));

        int deleted = 0;

        List<AttachmentService.Failed> failedAttachments =
                system.inScope(viewer, () -> attachments.failedWithObject(settledBefore, limit));
        for (AttachmentService.Failed row : failedAttachments) {
            try {
                store.delete(row.objectKey());
                ScopeContext owner = SystemScope.own(row.ownerEntityId(), null);
                if (system.inScope(owner, () -> attachments.objectDeleted(row, owner))) {
                    deleted++;
                }
            } catch (RuntimeException e) {
                log.error("Could not delete the object of failed attachment {}", row.attachmentId(), e);
            }
        }

        List<ObjectStorageService.Failed> failedObjects =
                system.inScope(viewer, () -> objects.failedWithObject(settledBefore, limit));
        for (ObjectStorageService.Failed row : failedObjects) {
            try {
                store.delete(row.objectKey());
                ScopeContext owner = SystemScope.own(row.ownerEntityId(), null);
                if (system.inScope(owner, () -> objects.objectDeleted(row, owner))) {
                    deleted++;
                }
            } catch (RuntimeException e) {
                log.error("Could not delete failed object {}", row.objectKey(), e);
            }
        }

        return deleted;
    }
}
