package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Result of a stock lot repricing operation (PGL-M2-03).
 */
public record StockRepricingResult(
        UUID repricingId,
        UUID stockLotId,
        UUID skuId,
        UUID batchId,
        LotCondition condition,
        BigDecimal qtyAffected,
        BigDecimal previousUnitCost,
        BigDecimal newUnitCost,
        LotCondition targetCondition,
        UUID targetLotId,
        BigDecimal valueAdjustment,
        String reason,
        UUID repricedBy,
        Instant repricedAt) {}
