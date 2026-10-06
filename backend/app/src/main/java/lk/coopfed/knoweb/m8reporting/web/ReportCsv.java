package lk.coopfed.knoweb.m8reporting.web;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.Messages;
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

    private static String text(String value) {
        if (!value.isEmpty() && FORMULA_START.indexOf(value.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    private static String quote(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
