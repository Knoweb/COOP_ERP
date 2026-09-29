package lk.coopfed.knoweb.m4trading.api;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** transfer_request.requested.v1 (doc 24 section 5): a shop asked for stock from another location of its society. */
public record TransferRequested(
        UUID requestId, UUID ownerEntityId, UUID fromLocationId, UUID toLocationId, List<TransferRequestLine> lines)
        implements DomainEvent {

    public static final String TYPE = "transfer_request.requested.v1";
}
