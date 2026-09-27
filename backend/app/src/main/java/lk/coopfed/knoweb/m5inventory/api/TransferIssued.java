package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A transfer left its source location and is in transit (doc 25 section 5.3, "transfer.*.v1").
 * Its TRANSFER_OUT movements are each announced by {@code stock.moved.v1}.
 */
public record TransferIssued(UUID transferId, UUID ownerEntityId, UUID fromLocationId, UUID toLocationId, int lines)
        implements DomainEvent {

    public static final String TYPE = "transfer.issued.v1";
}
