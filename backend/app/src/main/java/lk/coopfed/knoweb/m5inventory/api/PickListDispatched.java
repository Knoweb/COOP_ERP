package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * The vehicle left: the pick list's lots moved out of the seller's stock (TRANSFER_OUT, in transit
 * and still the seller's until the buyer's GRN, doc 24 A-03) and the reservation ended.
 */
public record PickListDispatched(UUID pickListId, UUID ownerEntityId, UUID deliveryDocumentId, int movements)
        implements DomainEvent {

    public static final String TYPE = "pick_list.dispatched.v1";
}
