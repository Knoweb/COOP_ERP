package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * An issued delivery note's pick list (25A section 6.2, "PickListService.create(dn): FEFO
 * suggestions per line; reservation = issued-undispatched qty"): the seller's lots are reserved
 * for it until dispatch.
 *
 * @param shortQty what the seller's stock could not cover, over all lines; zero when all is picked
 */
public record PickListCreated(
        UUID pickListId, UUID ownerEntityId, UUID deliveryDocumentId, BigDecimal pickedQty, BigDecimal shortQty)
        implements DomainEvent {

    public static final String TYPE = "pick_list.created.v1";
}
