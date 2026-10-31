package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;

/**
 * Historical stock lot repricing record for audit and view queries (PGL-M2-03).
 */
public record StockRepricingView(
        UUID repricingId,
        UUID ownerEntityId,
        UUID locationId,
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
