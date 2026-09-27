package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;

/**
 * An administrator moved a device's sync cursor past sequence numbers the device can no longer
 * send (doc 32 section 8, "sequence reset (recovery)"; section 7, "till outbox lost"): the numbers
 * {@code fromSeq} to {@code toSeq} are a documented gap, never received, and the device's next
 * batch starts at {@code toSeq + 1}. An ALERT audit record ({@code SYNC_SEQUENCE_RESET}) stands
 * beside it. No reason text travels here: the audit record keeps it.
 */
public record SyncSequenceReset(UUID gapId, UUID deviceId, long fromSeq, long toSeq, String reasonCode)
        implements DomainEvent {

    public static final String TYPE = "sync.sequence_reset.v1";
}
