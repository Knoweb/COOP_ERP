package lk.coopfed.knoweb.kernel.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A document line (doc 18 §5, table {@code document_line}). Immutable with its header;
 * the facts needed to reproduce a price or a stock effect are stored here, never
 * looked up later. Money scale 2, unit prices and costs scale 4, quantities scale 3.
 */
public record DocumentLineRecord(
        UUID id,
        UUID documentId,
        int lineNo,
        UUID skuId,
        UUID batchId,
        String uomCode,
        BigDecimal qty,
        BigDecimal unitPrice,
        BigDecimal mrpApplied,
        BigDecimal controlPriceApplied,
        String capReason,
        UUID discountRuleId,
        BigDecimal discountAmount,
        BigDecimal taxRatePercent,
        BigDecimal taxAmount,
        BigDecimal lineTotal,
        BigDecimal unitCostAtIssue,
        String lossCategory,
        UUID referenceLineId) {
}
