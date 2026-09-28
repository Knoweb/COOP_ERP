package lk.coopfed.knoweb.m4trading.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A discrepancy as the buyer who raised it and the seller it was raised with see it (doc 24
 * section 3.4). {@code status} is RAISED, or SETTLED once a credit note of the seller settles it
 * ({@code creditNoteId}); {@code invoiceId} is the seller's invoice of the GRN, when there is one.
 */
public record DiscrepancyView(
        UUID discrepancyId,
        String docNumberDisplay,
        String status,
        String kind,
        UUID buyerEntityId,
        UUID sellerEntityId,
        UUID grnId,
        String grnDocNumberDisplay,
        UUID deliveryNoteId,
        Instant raisedAt,
        Instant windowEndsAt,
        UUID invoiceId,
        UUID creditNoteId,
        String creditNoteDocNumberDisplay,
        List<DiscrepancyLineView> lines) {

    public static final String RAISED = "RAISED";
    public static final String SETTLED = "SETTLED";

    /** A varying GRN line: {@code varianceQty} is received less expected (negative when short). */
    public record DiscrepancyLineView(
            UUID lineId,
            UUID grnLineId,
            UUID skuId,
            UUID batchId,
            String uomCode,
            BigDecimal expectedQty,
            BigDecimal receivedQty,
            BigDecimal damagedQty,
            BigDecimal varianceQty,
            BigDecimal unitPrice) {}
}
