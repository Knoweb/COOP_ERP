package lk.coopfed.knoweb.m8reporting.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.m8reporting.query.FinalAccountsReport;
import lk.coopfed.knoweb.m8reporting.query.ReportTable;
import lk.coopfed.knoweb.m8reporting.query.ReportTable.Column;
import org.junit.jupiter.api.Test;

/**
 * The CSV's guard against a cell a spreadsheet would run (wave 2, M8-10): a TEXT cell that starts
 * with = + - @, or with a tab, carriage return or line feed, gets a leading apostrophe; a MONEY
 * cell that starts with "-" is a negative number and is written as it is.
 */
class ReportCsvTest {

    private static final Messages LABELS = (id, locale, args) -> new Messages.Text(id, false);

    @Test
    void aTextCellThatCouldStartAFormulaIsNeutralisedAndANegativeAmountIsNot() {
        ReportTable table = new ReportTable(
                "r",
                "t",
                List.of(new Column("name", "name", "TEXT"), new Column("amount", "amount", "MONEY")),
                List.of(
                        Map.of("name", "\t=HYPERLINK(\"x\")", "amount", "-12.50"),
                        Map.of("name", "\r=1+1", "amount", "3.00"),
                        Map.of("name", "\n@SUM(A1)", "amount", "0.00"),
                        Map.of("name", "=1+1", "amount", "1.00"),
                        Map.of("name", "Kuliyapitiya MPCS", "amount", "2.00")),
                Map.of(),
                null,
                Instant.parse("2026-10-06T00:00:00Z"));

        assertThat(ReportCsv.write(table, LABELS, Locale.ENGLISH))
                .startsWith("name,amount\r\n")
                .contains("\"'\t=HYPERLINK(\"\"x\"\")\",-12.50")
                .contains("\"'\r=1+1\",3.00")
                .contains("\"'\n@SUM(A1)\",0.00")
                .contains("'=1+1,1.00")
                .contains("Kuliyapitiya MPCS,2.00")
                .doesNotContain(",'-12.50");
    }

    @Test
    void finalAccountsReportCsvFormatsHeadersKpisCategoriesAndDrilldown() {
        FinalAccountsReport report = new FinalAccountsReport(
                "2026-10",
                "LKR",
                FinalAccountsReport.STATUS_COMPLETE,
                null,
                new BigDecimal("500000.00"),
                new BigDecimal("350000.00"),
                false,
                new BigDecimal("150000.00"),
                new BigDecimal("30.00"),
                new BigDecimal("45000.00"),
                new BigDecimal("25000.00"),
                false,
                new BigDecimal("70000.00"),
                new BigDecimal("80000.00"),
                new BigDecimal("16.00"),
                List.of(
                        new FinalAccountsReport.ExpenseCategoryItem(
                                "STAFF_PAYROLL",
                                "Staff & Labor (Approved Payroll / OT)",
                                new BigDecimal("45000.00"),
                                "APPROVED",
                                "Approved overtime and payroll integration from M3-05"),
                        new FinalAccountsReport.ExpenseCategoryItem(
                                "OTHER_OPERATING",
                                "Other Operating Expenses",
                                new BigDecimal("25000.00"),
                                "APPROVED",
                                "General ledger journal integration")),
                List.of(new FinalAccountsReport.PayrollContributionLineItem(
                        UUID.fromString("0190e888-0000-7000-8000-000000000101"),
                        UUID.fromString("0190e888-0000-7000-8000-000000000201"),
                        "EMP-001",
                        "Nimal Perera",
                        "OVERTIME",
                        new BigDecimal("45000.00"),
                        120,
                        Instant.parse("2026-10-31T10:00:00Z"),
                        "MN-2026-10-001")));

        String csv = ReportCsv.writeFinalAccounts(report, LABELS, Locale.ENGLISH);

        assertThat(csv)
                .contains("FINAL ACCOUNTS STATEMENT")
                .contains("Period,2026-10")
                .contains("Currency,LKR")
                .contains("Status,COMPLETE")
                .contains("Revenue,500000.00")
                .contains("Cost of Goods Sold,350000.00")
                .contains("Gross Profit,150000.00")
                .contains("Gross Profit Margin %,30.00")
                .contains("Approved Payroll Expenses,45000.00")
                .contains("Other Operating Expenses,25000.00")
                .contains("Total Operating Expenses,70000.00")
                .contains("Operating Profit,80000.00")
                .contains("Operating Profit Margin %,16.00")
                .contains("EXPENSE CATEGORIES")
                .contains("STAFF_PAYROLL,Staff & Labor (Approved Payroll / OT),45000.00,APPROVED")
                .contains("OTHER_OPERATING,Other Operating Expenses,25000.00,APPROVED")
                .contains("APPROVED PAYROLL CONTRIBUTIONS")
                .contains(
                        "0190e888-0000-7000-8000-000000000101,EMP-001,Nimal Perera,OVERTIME,120,45000.00,MN-2026-10-001");
    }

    @Test
    void finalAccountsReportCsvHandlesPendingCostAndUnavailableExpensesProperly() {
        FinalAccountsReport report = new FinalAccountsReport(
                "2026-11",
                "LKR",
                FinalAccountsReport.STATUS_PARTIAL,
                "Cost valuation pending for 1 or more retail shops in this period; non-payroll operating expenses (rent, utilities) pending Phase 3 general ledger integration",
                new BigDecimal("10000.00"),
                null,
                true,
                null,
                null,
                new BigDecimal("15000.00"),
                null,
                true,
                null,
                null,
                null,
                List.of(
                        new FinalAccountsReport.ExpenseCategoryItem(
                                "STAFF_PAYROLL",
                                "Staff & Labor (Approved Payroll / OT)",
                                new BigDecimal("15000.00"),
                                "APPROVED",
                                null),
                        new FinalAccountsReport.ExpenseCategoryItem(
                                "OTHER_OPERATING",
                                "Other Operating Expenses",
                                null,
                                "UNAVAILABLE",
                                "Pending Phase 3 general ledger")),
                List.of());

        String csv = ReportCsv.writeFinalAccounts(report, LABELS, Locale.ENGLISH);

        assertThat(csv)
                .contains("Period,2026-11")
                .contains("Status,PARTIAL")
                .contains(
                        "Status Reason,\"Cost valuation pending for 1 or more retail shops in this period; non-payroll operating expenses (rent, utilities) pending Phase 3 general ledger integration\"")
                .contains("Revenue,10000.00")
                .contains("Cost of Goods Sold,PENDING")
                .contains("Gross Profit,PENDING")
                .contains("Gross Profit Margin %,PENDING")
                .contains("Approved Payroll Expenses,15000.00")
                .contains("Other Operating Expenses,UNAVAILABLE")
                .contains("Total Operating Expenses,UNAVAILABLE")
                .contains("Operating Profit,UNAVAILABLE")
                .contains("Operating Profit Margin %,UNAVAILABLE");
    }
}
