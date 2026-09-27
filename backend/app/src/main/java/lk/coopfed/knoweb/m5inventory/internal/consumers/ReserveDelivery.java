package lk.coopfed.knoweb.m5inventory.internal.consumers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * PickListService.create(dn) of 25A section 6.2, read from delivery_note.issued.v1 by
 * {@link DeliveryConsumer}: the lines of every drop, in the SKU's base unit.
 */
record ReserveDelivery(UUID deliveryNoteId, UUID sellerEntityId, List<Line> lines) {

    /** A delivery line; {@code batchId} when the seller keyed or picked one, else null. */
    record Line(UUID lineId, UUID skuId, UUID batchId, BigDecimal qty) {}
}
