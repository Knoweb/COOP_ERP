package lk.coopfed.knoweb.m4trading.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * order.created.v1: the buyer drafted an order. 24A section 6 names no event for CreateOrder;
 * every command publishes one (AGENTS.md), so the draft is announced like the rest.
 */
public record OrderCreated(
        UUID orderId,
        UUID relationshipId,
        UUID buyerEntityId,
        UUID sellerEntityId,
        LocalDate requestedEta,
        List<OrderLineSummary> lines)
        implements DomainEvent {

    public static final String TYPE = "order.created.v1";
}
