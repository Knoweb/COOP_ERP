package lk.coopfed.knoweb.m4trading.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** order.submitted.v1 (doc 24 section 5.3): the buyer issued the order from its ENTITY series. */
public record OrderSubmitted(
        UUID orderId,
        String docNumberDisplay,
        UUID relationshipId,
        UUID buyerEntityId,
        UUID sellerEntityId,
        LocalDate requestedEta,
        List<OrderLineSummary> lines)
        implements DomainEvent {

    public static final String TYPE = "order.submitted.v1";
}
