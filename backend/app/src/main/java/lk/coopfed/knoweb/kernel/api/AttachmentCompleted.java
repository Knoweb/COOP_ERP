package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;

/**
 * The bytes of an attachment are in the store and match what was announced (19A section 9).
 * M5's write-off approval and M2's thumbnail job consume it.
 */
public record AttachmentCompleted(UUID attachmentId, UUID documentId, String contentHash, UUID ownerEntityId)
        implements DomainEvent {

    public static final String TYPE = "attachment.completed.v1";
}
