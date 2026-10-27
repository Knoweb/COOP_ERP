package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row in a bulk opening stock import payload (CAT-11).
 *
 * <p>Either references an existing batch known to M2 ({@code batchId}), or carries the batch
 * attributes ({@code batchNo}, {@code expiryDate}, {@code printedMrp}) alongside the item
 * reference ({@code skuId} or textual {@code sku} code/barcode).
 */
public record OpeningStockRow(
        String sku,
        UUID skuId,
        UUID batchId,
        String batchNo,
        LocalDate expiryDate,
        BigDecimal printedMrp,
        LotCondition condition,
        BigDecimal qty,
        BigDecimal unitCost) {

    public OpeningStockRow(
            String sku,
            BigDecimal qty,
            BigDecimal unitCost,
            String batchNo,
            LocalDate expiryDate,
            BigDecimal printedMrp,
            LotCondition condition) {
        this(sku, null, null, batchNo, expiryDate, printedMrp, condition, qty, unitCost);
    }
}
