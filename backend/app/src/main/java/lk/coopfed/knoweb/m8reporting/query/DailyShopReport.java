package lk.coopfed.knoweb.m8reporting.query;

import java.util.List;

/**
 * Complete daily operational sales report including summary and shop breakdown.
 */
public record DailyShopReport(DailyShopReportSummary summary, List<DailyShopReportItem> shops) {}
