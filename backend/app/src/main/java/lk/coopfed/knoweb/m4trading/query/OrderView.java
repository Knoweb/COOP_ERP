package lk.coopfed.knoweb.m4trading.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An order as both parties see it (doc 24 section 5.2, GetOrder). The status is derived
 * (CR-24A-1): DRAFT, SUBMITTED and CANCELLED from the buyer's document; ACCEPTED or REJECTED from
 * the seller's allocation; LOCKED from its lock time; PARTIALLY_FULFILLED and FULFILLED from the
 * allocation lines' fulfilled quantity.
 *
 * @param netAmount the indicative value of the order at the prices resolved when it was drafted
 * @param deliverToLocationId the buyer's location the goods go to, as the buyer named it (M4-11,
 *     CR-24A-2); null when it named none
 */
public record OrderView(
        UUID orderId,
        String docNumberDisplay,
        String status,
        UUID relationshipId,
        UUID buyerEntityId,
        UUID sellerEntityId,
        LocalDate requestedEta,
        LocalDate committedEta,
        Instant lockAt,
        Instant submittedAt,
        String rejectReasonCode,
        BigDecimal netAmount,
        String notes,
        List<OrderLineView> lines,
        UUID deliverToLocationId) {

    /**
     * @param indicativePrice the trade price per unit resolved when the order was drafted
     * @param allocatedQty    null until the seller accepted
     * @param fulfilledQty    null until the seller accepted
     * @param tierPrice       the price the seller resolved at acceptance; null until then
     */
    public record OrderLineView(
            UUID lineId,
            int lineNo,
            UUID skuId,
            String uomCode,
            BigDecimal requestedQty,
            BigDecimal cancelledQty,
            BigDecimal indicativePrice,
            BigDecimal allocatedQty,
            BigDecimal fulfilledQty,
            BigDecimal tierPrice) {}
}
