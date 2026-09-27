package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A transfer was received at its destination (doc 25 section 5.3, "transfer.*.v1"): its
 * TRANSFER_IN movements are posted there.
 */
public record TransferReceived(UUID transferId, UUID ownerEntityId, UUID fromLocationId, UUID toLocationId, int lines)
        implements DomainEvent {

    public static final String TYPE = "transfer.received.v1";
}
