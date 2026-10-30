package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A line item on a credit-purchase voucher document.
 */
public record CreditVoucherLineView(
        int lineNo,
        UUID skuId,
        String skuCode,
        String skuName,
        String uomCode,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal taxRatePercent,
        BigDecimal taxAmount,
        BigDecimal lineTotal) {}
