package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A transfer left its source location and is in transit (doc 25 section 5.3, "transfer.*.v1").
 * Its TRANSFER_OUT movements are each announced by {@code stock.moved.v1}. {@code
 * transferRequestId} names the M4 transfer request it fulfils, null for one issued by hand; {@code
 * shortLines} (wave 2, M5-03, additive) counts the request's items that could not be sent in full,
 * so a screen can show the request as partly filled; the shop raises a new request for the rest.
 */
public record TransferIssued(
        UUID transferId,
        UUID ownerEntityId,
        UUID fromLocationId,
        UUID toLocationId,
        int lines,
        UUID transferRequestId,
        int shortLines)
        implements DomainEvent {

    public static final String TYPE = "transfer.issued.v1";

    /** A transfer sent in full. */
    public TransferIssued(
            UUID transferId,
            UUID ownerEntityId,
            UUID fromLocationId,
            UUID toLocationId,
            int lines,
            UUID transferRequestId) {
        this(transferId, ownerEntityId, fromLocationId, toLocationId, lines, transferRequestId, 0);
    }

    /** A transfer issued by hand, for no request. */
    public TransferIssued(UUID transferId, UUID ownerEntityId, UUID fromLocationId, UUID toLocationId, int lines) {
        this(transferId, ownerEntityId, fromLocationId, toLocationId, lines, null, 0);
    }
}
