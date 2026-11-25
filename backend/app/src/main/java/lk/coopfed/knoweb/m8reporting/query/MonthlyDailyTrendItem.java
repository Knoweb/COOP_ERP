package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Daily operational metrics entry within a monthly trend breakdown.
 */
public record MonthlyDailyTrendItem(
        LocalDate businessDate,
        BigDecimal grossSales,
        BigDecimal netSales,
        BigDecimal taxAmount,
        long transactionCount,
        BigDecimal averageTransactionValue,
        BigDecimal itemsSold) {}
