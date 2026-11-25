package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A previously issued transfer has left its source and is now expected at its destination. */
public record TransferDispatched(UUID transferId, UUID ownerEntityId, UUID fromLocationId, UUID toLocationId, int lines)
        implements DomainEvent {

    public static final String TYPE = "transfer.dispatched.v1";
}
