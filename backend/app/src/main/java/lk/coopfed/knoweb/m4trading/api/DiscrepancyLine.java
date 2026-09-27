package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of a discrepancy (doc 24 section 3.4: "expected, received, damaged, variance").
 *
 * @param varianceQty received minus expected: negative when short, positive when over
 */
public record DiscrepancyLine(
        UUID grnLineId,
        UUID skuId,
        UUID batchId,
        String uomCode,
        BigDecimal expectedQty,
        BigDecimal receivedQty,
        BigDecimal damagedQty,
        BigDecimal varianceQty) {}
