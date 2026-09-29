package lk.coopfed.knoweb.m4trading.api;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * transfer_request.approved.v1 (doc 24 section 5): the society approved; M5 issues the transfer
 * from {@code fromLocationId} to {@code toLocationId}, picking batches first-expiry-first, and
 * names the request on it (25A section 6.2).
 */
public record TransferRequestApproved(
        UUID requestId,
        UUID ownerEntityId,
        UUID fromLocationId,
        UUID toLocationId,
        UUID decidedByUserId,
        List<TransferRequestLine> lines)
        implements DomainEvent {

    public static final String TYPE = "transfer_request.approved.v1";
}
