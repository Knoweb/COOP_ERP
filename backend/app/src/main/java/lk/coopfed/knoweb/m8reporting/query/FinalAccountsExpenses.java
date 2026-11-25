package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.util.List;

/**
 * Monthly operating expense breakdown including approved staff/payroll contributions (Polgolla M1-09).
 */
public record FinalAccountsExpenses(
        String period,
        String currency,
        BigDecimal totalPayrollExpenses,
        int contributionCount,
        List<FinalAccountsReport.ExpenseCategoryItem> categories,
        List<FinalAccountsReport.PayrollContributionLineItem> contributions) {}
