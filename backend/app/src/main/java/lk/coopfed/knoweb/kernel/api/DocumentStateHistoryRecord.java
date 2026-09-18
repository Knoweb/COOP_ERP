package lk.coopfed.knoweb.kernel.api;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One state transition (doc 18 §5, table {@code document_state_history}, append-only).
 * The document's current status is the {@code toStatus} of its last row.
 */
public record DocumentStateHistoryRecord(
        UUID id,
        UUID documentId,
        String fromStatus,
        String toStatus,
        Instant occurredAt,
        LocalDateTime occurredLocal,
        UUID actorUserId,
        UUID deviceId,
        String reasonCode,
        String reasonText) {
}
