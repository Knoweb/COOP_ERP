package lk.coopfed.knoweb.m4trading.api;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** order.allocated.v1 (doc 24 section 5.3): the allocation run's result for one order; M5 reads it for availability. */
public record OrderAllocated(
        UUID orderId, UUID allocationRunId, UUID sellerEntityId, UUID buyerEntityId, List<OrderLineSummary> lines)
        implements DomainEvent {

    public static final String TYPE = "order.allocated.v1";
}
