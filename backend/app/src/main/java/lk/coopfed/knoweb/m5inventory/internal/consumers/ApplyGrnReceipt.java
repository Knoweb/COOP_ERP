package lk.coopfed.knoweb.m5inventory.internal.consumers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ApplyReceipt of doc 25 section 5.1 ("internal, from grn.confirmed: grn lines with batch, qty,
 * damaged, cost"), read from grn.confirmed.v1 by {@link GrnConsumer}.
 */
record ApplyGrnReceipt(
        UUID grnId, UUID receiverEntityId, UUID receiverLocationId, Instant confirmedAt, List<Line> lines) {

    /** A GRN line: {@code receivedQty - damagedQty} GOOD and {@code damagedQty} DAMAGED, at {@code unitCost}. */
    record Line(UUID lineId, UUID batchId, BigDecimal receivedQty, BigDecimal damagedQty, BigDecimal unitCost) {}
}
