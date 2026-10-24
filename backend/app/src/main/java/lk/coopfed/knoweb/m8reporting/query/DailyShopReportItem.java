package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Daily operational sales metrics for a single shop.
 */
public record DailyShopReportItem(
        UUID locationId,
        String locationCode,
        String locationName,
        LocalDate businessDate,
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
