package lk.coopfed.knoweb.m4trading.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * claim.return_dispatched.v1: the buyer sent the accepted goods of a claim back from the GRN's
 * location. Published in the buyer's scope, so M5 posts RETURN_TO_SELLER from the buyer's lots
 * (25A section 6.2, ClaimReturn) under the buyer's own row-level security.
 */
public record ClaimReturnDispatched(
        UUID claimId,
        UUID buyerEntityId,
        UUID sellerEntityId,
        UUID locationId,
        Instant dispatchedAt,
        List<ClaimLine> lines)
        implements DomainEvent {

    public static final String TYPE = "claim.return_dispatched.v1";
}
