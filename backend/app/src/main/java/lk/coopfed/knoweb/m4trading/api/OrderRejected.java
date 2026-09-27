package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** order.rejected.v1 (doc 24 section 5.3): the seller refused a submitted order, with a reason. */
public record OrderRejected(
        UUID orderId, UUID relationshipId, UUID buyerEntityId, UUID sellerEntityId, String reasonCode)
        implements DomainEvent {

    public static final String TYPE = "order.rejected.v1";
}
