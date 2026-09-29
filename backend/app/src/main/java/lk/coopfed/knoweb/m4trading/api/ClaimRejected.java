package lk.coopfed.knoweb.m4trading.api;

import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** claim.rejected.v1 (doc 24 section 5): the seller refused the claim, with a reason. */
public record ClaimRejected(
        UUID claimId,
        UUID grnId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        String reason,
        UUID decidedByUserId,
        Instant decidedAt)
        implements DomainEvent {

    public static final String TYPE = "claim.rejected.v1";
}
