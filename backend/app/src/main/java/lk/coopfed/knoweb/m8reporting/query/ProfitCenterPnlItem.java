package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * P&L metrics for one profit center or unallocated bucket in a monthly period.
 */
public record ProfitCenterPnlItem(
        UUID profitCenterId,
        String code,
        String name,
        String category,
        UUID locationId,
        String locationName,
        BigDecimal revenue,
        BigDecimal cost,
        BigDecimal grossProfit,
        BigDecimal marginPercent,
        boolean costPending,
        long txnCount) {}
