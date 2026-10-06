package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** transfer_request.rejected.v1 (doc 24 section 5): the society refused the request, with a reason. */
public record TransferRequestRejected(
        UUID requestId, UUID ownerEntityId, UUID fromLocationId, UUID toLocationId, String reason, UUID decidedByUserId)
        implements DomainEvent {

    public static final String TYPE = "transfer_request.rejected.v1";
}
