package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * An approved transfer request could not be filled at all: the source held none of any requested
 * item by the time the delivery ran, so no transfer was issued (wave 3, M1M2M3M5-17). Recorded as
 * {@code TRANSFER_REQUEST_SHORT} (REVIEW), like a request sent short, so a person sees it; the shop
 * raises a new request when the stock is there.
 *
 * @param transferRequestId the request M4 approved
 * @param shortLines        the requested items, every one of them sent at nothing
 */
public record TransferRequestUnfilled(
        UUID transferRequestId, UUID ownerEntityId, UUID fromLocationId, UUID toLocationId, int shortLines)
        implements DomainEvent {

    public static final String TYPE = "transfer_request.unfilled.v1";
}
