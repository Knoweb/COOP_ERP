package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One counted line of an opening balance (doc 25 section 3.7: "counted quantities with batch data
 * and cost"). The batch is registered in M2 beforehand; its SKU is the batch's.
 */
public record OpeningBalanceLine(UUID batchId, LotCondition condition, BigDecimal qty, BigDecimal unitCost) {}
