package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One counted line of an opening balance (doc 25 section 3.7: "counted quantities with batch data
 * and cost"). Either the batch is registered in M2 beforehand ({@code batchId}; its SKU is the
 * batch's), or the line carries the batch data as counted ({@code skuId}, {@code batchNo},
 * {@code expiryDate}, {@code printedMrp}). The latter data is retained with the signed line and
 * CountersignOpeningBalance registers the batch through M2 in the posting transaction (M5-10b,
 * M5-19), so an abandoned draft never leaves a permanent catalogue batch.
 */
public record OpeningBalanceLine(
        UUID batchId,
        LotCondition condition,
        BigDecimal qty,
        BigDecimal unitCost,
        UUID skuId,
        String batchNo,
        LocalDate expiryDate,
        BigDecimal printedMrp) {

    /** A line on a batch M2 already knows. */
    public OpeningBalanceLine(UUID batchId, LotCondition condition, BigDecimal qty, BigDecimal unitCost) {
        this(batchId, condition, qty, unitCost, null, null, null, null);
    }
}
