package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Reprices a stock lot or part of it with a new unit cost and optional condition downgrade (PGL-M2-03).
 */
public record RepriceStockLot(
        UUID stockLotId, BigDecimal qty, BigDecimal newUnitCost, LotCondition targetCondition, String reason) {}
