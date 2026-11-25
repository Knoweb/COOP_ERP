package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A line item in the goods supplied summary report.
 */
public record GoodsSuppliedLineItemView(
        UUID invoiceId,
        String invoiceDocNumber,
        LocalDate invoiceDate,
        UUID societyEntityId,
        String societyName,
        UUID skuId,
        String skuCode,
        String skuName,
        String uomCode,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal taxRatePercent,
        BigDecimal taxAmount,
        BigDecimal lineTotal) {}
