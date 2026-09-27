package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * order.cancelled.v1 (doc 24 section 5.3): the undispatched remainder of an order was cancelled.
 *
 * @param cancelledByEntityId the buyer or the seller
 */
public record OrderCancelled(
        UUID orderId,
        UUID relationshipId,
        UUID buyerEntityId,
        UUID sellerEntityId,
        UUID cancelledByEntityId,
        String reasonCode)
        implements DomainEvent {

    public static final String TYPE = "order.cancelled.v1";
}
