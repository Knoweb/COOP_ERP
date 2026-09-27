package lk.coopfed.knoweb.m4trading.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * order.accepted.v1 (doc 24 section 5.3): the seller accepted a submitted order with its
 * allocations, a committed ETA and the lock time. The exposure consumer (M4-09) reads it.
 */
public record OrderAccepted(
        UUID orderId,
        UUID relationshipId,
        UUID buyerEntityId,
        UUID sellerEntityId,
        UUID allocationRunId,
        LocalDate committedEta,
        Instant lockAt,
        List<OrderLineSummary> lines)
        implements DomainEvent {

    public static final String TYPE = "order.accepted.v1";
}
