package lk.coopfed.knoweb.m4trading.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A claim as both parties read it (doc 24 section 3.5): the buyer's claim with the seller's
 * decision and the buyer's return beside it. {@code status} is RAISED, APPROVED or REJECTED, from
 * the seller's decision row; {@code returnedAt} is set once the buyer sent the goods back.
 */
public record ClaimView(
        UUID claimId,
        String docNumberDisplay,
        String status,
        String kind,
        UUID buyerEntityId,
        UUID sellerEntityId,
        UUID locationId,
        UUID grnId,
        String grnDocNumberDisplay,
        Instant raisedAt,
        Instant windowEndsAt,
        boolean returnRequested,
        String note,
        UUID invoiceId,
        String findings,
        String rejectReason,
        boolean returnRequired,
        UUID creditNoteId,
        String creditNoteDocNumberDisplay,
        UUID decidedByUserId,
        Instant decidedAt,
        Instant returnedAt,
        List<Photo> photos,
        List<Line> lines) {

    public static final String RAISED = "RAISED";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    /** A photograph and its upload state: PENDING, COMPLETE or FAILED. */
    public record Photo(UUID attachmentId, String status) {}

    /** A claimed line; {@code approvedQty} once the seller decided, {@code unitPrice} from the invoice once billed. */
    public record Line(
            UUID claimLineId,
            UUID grnLineId,
            UUID skuId,
            UUID batchId,
            String uomCode,
            BigDecimal claimedQty,
            BigDecimal approvedQty,
            BigDecimal unitPrice) {}
}
