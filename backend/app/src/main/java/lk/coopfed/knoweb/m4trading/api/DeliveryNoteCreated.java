package lk.coopfed.knoweb.m4trading.api;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** delivery_note.created.v1: the seller drafted a delivery note (every command publishes, AGENTS.md). */
public record DeliveryNoteCreated(
        UUID deliveryNoteId, UUID sellerEntityId, UUID buyerEntityId, List<UUID> orderIds, List<DropSummary> drops)
        implements DomainEvent {

    public static final String TYPE = "delivery_note.created.v1";
}
