package lk.coopfed.knoweb.m4trading.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** claim.raised.v1 (doc 24 section 5): the buyer raised a claim against the seller of a GRN. */
public record ClaimRaised(
        UUID claimId,
        String docNumberDisplay,
        UUID grnId,
        UUID buyerEntityId,
        UUID sellerEntityId,
        UUID locationId,
        String kind,
        boolean returnRequested,
        Instant windowEndsAt,
        List<ClaimLine> lines)
        implements DomainEvent {

    public static final String TYPE = "claim.raised.v1";
}
