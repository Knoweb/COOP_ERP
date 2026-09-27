package lk.coopfed.knoweb.m4trading.api;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * delivery_note.issued.v1 (doc 24 section 5.3): the seller issued the delivery note from its
 * ENTITY series. M5 picks the batches; the snapshot contributor (deferred) sends each drop's
 * expected lines to its shop.
 */
public record DeliveryNoteIssued(
        UUID deliveryNoteId,
        String docNumberDisplay,
        UUID sellerEntityId,
        UUID buyerEntityId,
        List<UUID> orderIds,
        List<DropSummary> drops)
        implements DomainEvent {

    public static final String TYPE = "delivery_note.issued.v1";
}
