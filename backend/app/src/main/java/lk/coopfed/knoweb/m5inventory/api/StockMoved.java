package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * One movement posted to the ledger (doc 25 section 5.3: "location, batch, condition, qty delta,
 * type, cost at movement, document"). Consumers: M2's assortment, the lot snapshot, M3's in-stock
 * MRPs, M8's projections.
 *
 * @param lotQtyOnHand the lot's quantity after the movement, so a projection needs no read back
 */
public record StockMoved(
        UUID movementId,
        UUID ownerEntityId,
        UUID locationId,
        UUID stockLotId,
        UUID batchId,
        UUID skuId,
        String condition,
        String movementType,
        BigDecimal qtyDelta,
        BigDecimal unitCostAtMovement,
        BigDecimal lotQtyOnHand,
        UUID documentId,
        String source,
        long movementSeq)
        implements DomainEvent {

    public static final String TYPE = "stock.moved.v1";
}
