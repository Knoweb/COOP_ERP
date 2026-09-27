package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A delivery note's pick list (25A section 5, {@code /pick-lists/{dnId}}): OPEN while it reserves
 * the seller's lots, DISPATCHED once the vehicle left.
 */
public record PickListView(UUID pickListId, UUID deliveryDocumentId, String status, List<Pick> lines) {

    /**
     * One pick: a lot and a quantity, or, with no lot, the part of the delivery line the seller's
     * stock did not cover.
     */
    public record Pick(
            UUID deliveryLineId, UUID skuId, UUID locationId, UUID stockLotId, UUID batchId, BigDecimal qty) {}
}
