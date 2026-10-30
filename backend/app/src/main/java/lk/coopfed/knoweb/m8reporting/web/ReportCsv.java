package lk.coopfed.knoweb.m8reporting.web;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.m8reporting.query.CreditorSummaryItemView;
import lk.coopfed.knoweb.m8reporting.query.CreditorsReport;
import lk.coopfed.knoweb.m8reporting.query.DebtorSummaryItemView;
import lk.coopfed.knoweb.m8reporting.query.DebtorsReport;
import lk.coopfed.knoweb.m8reporting.query.GoodsSuppliedLineItemView;
import lk.coopfed.knoweb.m8reporting.query.GoodsSuppliedReport;
import lk.coopfed.knoweb.m8reporting.query.OpeningBalanceItemView;
import lk.coopfed.knoweb.m8reporting.query.OpeningBalancesReport;
import lk.coopfed.knoweb.m8reporting.query.ReportTable;
import lk.coopfed.knoweb.m8reporting.query.ReportTable.Column;

/**
 * A report as CSV (RFC 4180): a header line of the column names in the caller's language, then
 * one line per row, values as the data operation gives them. A text cell that a spreadsheet
 * would take for a formula (it starts with =, +, -, @, a tab, a carriage return or a line feed)
 * is written with a leading apostrophe, so opening the file never runs anything; numbers and
 * dates are written as they are.
 */
final class ReportCsv {

    /**
     * The first characters a spreadsheet may read as the start of a formula: = + - @, and a tab,
     * carriage return or line feed in front of one (wave 2, M8-10). Only TEXT cells are guarded: a
     * MONEY or QTY cell starts with "-" when it is negative, and is a number.
     */
    static final String FORMULA_START = "=+-@\t\r\n";

    private ReportCsv() {}

    static String write(ReportTable table, Messages messages, Locale locale) {
        StringBuilder csv = new StringBuilder();
        List<Column> columns = table.columns();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(quote(text(messages.t(columns.get(i).labelId(), locale))));
        }
        csv.append("\r\n");
        for (Map<String, String> row : table.rows()) {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    csv.append(',');
                }
                Column column = columns.get(i);
                String value = row.getOrDefault(column.key(), "");
                csv.append(quote("TEXT".equals(column.kind()) ? text(value) : value));
            }
            csv.append("\r\n");
        }
        return csv.toString();
    }

    static String writeDebtors(DebtorsReport report, Messages messages, Locale locale) {
        StringBuilder csv = new StringBuilder();
        csv.append(quote(text(messages.t("m8.finance.debtor.code", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.debtor.name", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.debtor.limit", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.debtor.open_invoices", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.debtor.accepted_not_invoiced", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.debtor.unapplied_receipts", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.debtor.unapplied_credits", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.debtor.net_exposure", locale))))
                .append("\r\n");

        for (DebtorSummaryItemView item : report.items()) {
            csv.append(quote(text(item.counterpartyCode())))
                    .append(',')
                    .append(quote(text(item.counterpartyName())))
                    .append(',')
                    .append(quote(
                            item.creditLimit() != null ? item.creditLimit().toPlainString() : ""))
                    .append(',')
                    .append(quote(item.openInvoices().toPlainString()))
                    .append(',')
                    .append(quote(item.acceptedNotInvoiced().toPlainString()))
                    .append(',')
                    .append(quote(item.unappliedReceipts().toPlainString()))
                    .append(',')
                    .append(quote(item.unappliedCredits().toPlainString()))
                    .append(',')
                    .append(quote(item.netExposure().toPlainString()))
                    .append("\r\n");
        }
        return csv.toString();
    }

    static String writeCreditors(CreditorsReport report, Messages messages, Locale locale) {
        StringBuilder csv = new StringBuilder();
        csv.append(quote(text(messages.t("m8.finance.creditor.code", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.creditor.name", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.creditor.open_invoices", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.creditor.accepted_not_invoiced", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.creditor.unapplied_payments", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.creditor.unapplied_credits", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.creditor.net_liability", locale))))
                .append("\r\n");

        for (CreditorSummaryItemView item : report.items()) {
            csv.append(quote(text(item.counterpartyCode())))
                    .append(',')
                    .append(quote(text(item.counterpartyName())))
                    .append(',')
                    .append(quote(item.openInvoices().toPlainString()))
                    .append(',')
                    .append(quote(item.acceptedNotInvoiced().toPlainString()))
                    .append(',')
                    .append(quote(item.unappliedPayments().toPlainString()))
                    .append(',')
                    .append(quote(item.unappliedCredits().toPlainString()))
                    .append(',')
                    .append(quote(item.netLiability().toPlainString()))
                    .append("\r\n");
        }
        return csv.toString();
    }

    static String writeOpeningBalances(OpeningBalancesReport report, Messages messages, Locale locale) {
        StringBuilder csv = new StringBuilder();
        csv.append(quote(text(messages.t("m8.finance.opening.doc_number", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.opening.counterparty_code", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.opening.counterparty_name", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.opening.role", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.opening.amount", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.opening.reference_no", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.opening.notes", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.opening.created_at", locale))))
                .append("\r\n");

        for (OpeningBalanceItemView item : report.items()) {
            csv.append(quote(text(item.docNumberDisplay())))
                    .append(',')
                    .append(quote(text(item.counterpartyCode())))
                    .append(',')
                    .append(quote(text(item.counterpartyName())))
                    .append(',')
                    .append(quote(text(item.role())))
                    .append(',')
                    .append(quote(item.amount().toPlainString()))
                    .append(',')
                    .append(quote(text(item.referenceNo() != null ? item.referenceNo() : "")))
                    .append(',')
                    .append(quote(text(item.notes() != null ? item.notes() : "")))
                    .append(',')
                    .append(quote(item.createdAt() != null ? item.createdAt().toString() : ""))
                    .append("\r\n");
        }
        return csv.toString();
    }

    static String writeGoodsSupplied(GoodsSuppliedReport report, Messages messages, Locale locale) {
        StringBuilder csv = new StringBuilder();
        csv.append(quote(text(messages.t("m8.finance.goods.invoice_no", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.goods.date", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.goods.society", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.goods.sku_code", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.goods.sku_name", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.goods.uom", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.goods.quantity", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.goods.unit_price", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.goods.tax_rate", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.goods.tax_amount", locale))))
                .append(',')
                .append(quote(text(messages.t("m8.finance.goods.line_total", locale))))
                .append("\r\n");

        for (GoodsSuppliedLineItemView line : report.lines()) {
            csv.append(quote(text(line.invoiceDocNumber())))
                    .append(',')
                    .append(quote(
                            line.invoiceDate() != null ? line.invoiceDate().toString() : ""))
                    .append(',')
                    .append(quote(text(line.societyName())))
                    .append(',')
                    .append(quote(text(line.skuCode())))
                    .append(',')
                    .append(quote(text(line.skuName())))
                    .append(',')
                    .append(quote(text(line.uomCode())))
                    .append(',')
                    .append(quote(line.quantity().toPlainString()))
                    .append(',')
                    .append(quote(line.unitPrice().toPlainString()))
                    .append(',')
                    .append(quote(line.taxRatePercent().toPlainString()))
                    .append(',')
                    .append(quote(line.taxAmount().toPlainString()))
                    .append(',')
                    .append(quote(line.lineTotal().toPlainString()))
                    .append("\r\n");
        }
        return csv.toString();
    }

    private static String text(String value) {
        if (value == null) {
            return "";
        }
        if (!value.isEmpty() && FORMULA_START.indexOf(value.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    private static String quote(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
