package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A transfer was cancelled before receipt and its stock was restored at the source. */
public record TransferCancelled(
        UUID transferId,
        UUID ownerEntityId,
        UUID fromLocationId,
        UUID toLocationId,
        String previousStatus,
        boolean physicalReturnVerified,
        int lines)
        implements DomainEvent {

    public static final String TYPE = "transfer.cancelled.v1";
}
