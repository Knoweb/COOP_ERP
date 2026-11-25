package lk.coopfed.knoweb.m8reporting.query;

import java.util.List;

/**
 * Complete monthly operational sales report including summary, daily trend, and shop comparison.
 */
public record MonthlyShopReport(
        MonthlyShopReportSummary summary, List<MonthlyDailyTrendItem> dailyTrend, List<MonthlyShopReportItem> shops) {}
