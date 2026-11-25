package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Summary totals across all permitted shops for a month.
 */
public record MonthlyShopReportSummary(
        String yearMonth,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal totalGrossSales,
        BigDecimal totalNetSales,
        BigDecimal totalTaxAmount,
        long totalTransactions,
        BigDecimal averageTransactionValue,
        BigDecimal totalItemsSold,
        BigDecimal totalTargetAmount,
        BigDecimal totalVarianceAmount,
        BigDecimal overallAchievementPercent,
        String currency) {}
