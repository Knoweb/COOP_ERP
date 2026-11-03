package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Monthly final accounts statement combining revenue, cost of goods sold, gross profit,
 * and approved operating/payroll expenses (Polgolla M1-09).
 */
public record FinalAccountsReport(
        String period,
        String currency,
        String status,
        String statusReason,
        BigDecimal revenue,
        BigDecimal cogs,
        boolean costPending,
        BigDecimal grossProfit,
        BigDecimal grossProfitMarginPercent,
        BigDecimal payrollExpenses,
        BigDecimal otherExpenses,
        boolean otherExpensesPending,
        BigDecimal totalExpenses,
        BigDecimal operatingProfit,
        BigDecimal operatingProfitMarginPercent,
        List<ExpenseCategoryItem> expenseCategories,
        List<PayrollContributionLineItem> payrollContributions) {

    public static final String STATUS_COMPLETE = "COMPLETE";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";

    public record ExpenseCategoryItem(
            String category, String categoryName, BigDecimal amount, String status, String notes) {}

    public record PayrollContributionLineItem(
            UUID contributionId,
            UUID employeeId,
            String employeeNo,
            String employeeName,
            String contributionType,
            BigDecimal approvedAmount,
            int approvedOtMinutes,
            Instant approvedAt,
            String status) {}
}
