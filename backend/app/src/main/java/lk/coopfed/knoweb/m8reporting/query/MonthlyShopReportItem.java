package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Monthly operational sales metrics and target comparison for a single shop.
 */
public record MonthlyShopReportItem(
        UUID locationId,
        String locationCode,
        String locationName,
        BigDecimal grossSales,
        BigDecimal netSales,
        BigDecimal taxAmount,
        long transactionCount,
        BigDecimal averageTransactionValue,
        BigDecimal itemsSold,
        BigDecimal targetAmount,
        BigDecimal varianceAmount,
        BigDecimal achievementPercent,
        ShopPerformanceState performanceState,
        String currency) {}
