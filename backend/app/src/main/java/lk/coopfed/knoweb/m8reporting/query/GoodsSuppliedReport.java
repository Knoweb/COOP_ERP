package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Summary of goods supplied across entities and locations for a daily or monthly period.
 */
public record GoodsSuppliedReport(
        LocalDate from,
        LocalDate to,
        UUID societyId,
        UUID locationId,
        List<GoodsSuppliedLineItemView> lines,
        BigDecimal totalQuantity,
        BigDecimal totalNetAmount,
        BigDecimal totalTaxAmount,
        BigDecimal totalGrossAmount,
        int lineCount) {}
