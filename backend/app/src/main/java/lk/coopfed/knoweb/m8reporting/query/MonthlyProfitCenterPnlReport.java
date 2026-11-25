package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.util.List;

/**
 * Month-end P&L report breakdown by profit centers for an entity.
 */
public record MonthlyProfitCenterPnlReport(
        String month,
        String currency,
        Summary summary,
        List<ProfitCenterPnlItem> items,
        ProfitCenterPnlItem unallocated) {

    public record Summary(
            BigDecimal totalRevenue,
            BigDecimal totalCost,
            BigDecimal grossProfit,
            BigDecimal marginPercent,
            boolean costPending) {}
}
