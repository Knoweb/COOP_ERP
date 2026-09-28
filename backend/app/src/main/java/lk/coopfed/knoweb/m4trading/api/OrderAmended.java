package lk.coopfed.knoweb.m4trading.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * order.amended.v1 (24A section 6, AmendOrder): the buyer replaced its order, before the seller
 * decided it, by the next version. The amended order is also cancelled (order.cancelled.v1, reason
 * ORDER_AMENDED) and a submitted amendment submitted (order.submitted.v1), so a reader of those
 * two events alone (M8's trade projection) stays right.
 *
 * @param orderId       the new version
 * @param amendsOrderId the version it replaces
 * @param status        DRAFT or SUBMITTED, as the version it replaces was
 */
public record OrderAmended(
        UUID orderId,
        UUID amendsOrderId,
        int version,
        String docNumberDisplay,
        UUID relationshipId,
        UUID buyerEntityId,
        UUID sellerEntityId,
        LocalDate requestedEta,
        String status,
        List<OrderLineSummary> lines)
        implements DomainEvent {

    public static final String TYPE = "order.amended.v1";
}
