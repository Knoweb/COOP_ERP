package lk.coopfed.knoweb.m4trading.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * claim.approved.v1 (doc 24 section 5): the seller accepted the claim; {@code lines} carry the
 * accepted quantities, {@code creditNoteId} the credit note issued with the decision. The goods go
 * back only when the buyer dispatches them ({@code claim.return_dispatched.v1}): this event is the
 * seller's, and M5 moves the buyer's stock only in the buyer's own scope (module README).
 */
public record ClaimApproved(
        UUID claimId,
        UUID grnId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        boolean returnRequired,
        UUID creditNoteId,
        UUID decidedByUserId,
        Instant decidedAt,
        List<ClaimLine> lines)
        implements DomainEvent {

    public static final String TYPE = "claim.approved.v1";
}
