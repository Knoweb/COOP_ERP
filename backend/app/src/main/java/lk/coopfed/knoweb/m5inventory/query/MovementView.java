package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One ledger movement as the reads show it (doc 18 part B, stock_movement). */
public record MovementView(
        UUID movementId,
        UUID locationId,
        UUID skuId,
        UUID batchId,
        String condition,
        String movementType,
        BigDecimal qtyDelta,
        BigDecimal unitCostAtMovement,
        UUID documentId,
        UUID documentLineId,
        String source,
        long movementSeq,
        Instant occurredAt) {}
