package lk.coopfed.knoweb.m4trading.api;

import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * delivery_note.dispatched.v1 (doc 24 section 5.3): the vehicle left. The driver's name stays on
 * the document; an event carries no person's name (doc 19 section 6.1).
 */
public record DeliveryNoteDispatched(
        UUID deliveryNoteId,
        String docNumberDisplay,
        UUID sellerEntityId,
        UUID buyerEntityId,
        Instant dispatchedAt,
        String vehicleRef,
        UUID driverUserId)
        implements DomainEvent {

    public static final String TYPE = "delivery_note.dispatched.v1";
}
