package lk.coopfed.knoweb.m8reporting.internal.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.Formats;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m8reporting.query.ReportParameters;
import lk.coopfed.knoweb.m8reporting.query.ReportTable;
import lk.coopfed.knoweb.m8reporting.query.ReportTable.Column;
import org.springframework.stereotype.Component;

/**
 * The model of M8's A4 template {@code m8-report} (resources/reports/templates/m8-report.html):
 * the report's rows formatted for print in the run's language. The template does no arithmetic
 * and no formatting (reports/templates/README.md): amounts, quantities and dates arrive here
 * formatted by the kernel's Formats, the labels translated by its Messages.
 *
 * <pre>
 * title, subtitle (the period), generated, freshness (may be absent),
 * columns: [{label, num}], rows: [[text, ...]], totals: [text, ...] (absent when nothing sums)
 * </pre>
 */
@Component
class ReportPrintModel {

    private final Messages messages;
    private final Formats formats;

    ReportPrintModel(Messages messages, Formats formats) {
        this.messages = messages;
        this.formats = formats;
    }

    Map<String, Object> model(ReportTable table, ReportParameters parameters, ScopeContext scope) {
        Locale locale = scope.locale();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", messages.t(table.titleId(), locale));
        if (parameters.from() != null && parameters.to() != null) {
            data.put(
                    "subtitle",
                    messages.t(
                            "m8.print.period", locale, formats.date(parameters.from()), formats.date(parameters.to())));
        }
        data.put("generated", messages.t("m8.print.generated", locale, formats.dateTime(table.generatedAt())));
        if (table.freshness() != null) {
            data.put("freshness", messages.t("m8.print.freshness", locale, formats.dateTime(table.freshness())));
        }

        List<Map<String, Object>> columns = new ArrayList<>();
        for (Column column : table.columns()) {
            columns.add(Map.of("label", messages.t(column.labelId(), locale), "num", numeric(column)));
        }
        data.put("columns", columns);

        List<List<String>> rows = new ArrayList<>();
        for (Map<String, String> row : table.rows()) {
            List<String> cells = new ArrayList<>();
            for (Column column : table.columns()) {
                cells.add(format(column, row.get(column.key()), locale));
            }
            rows.add(cells);
        }
        data.put("rows", rows);
        data.put("empty", messages.t("m8.print.empty", locale));

        if (!table.totals().isEmpty() && !table.rows().isEmpty()) {
            List<String> totals = new ArrayList<>();
            boolean labelled = false;
            for (Column column : table.columns()) {
                String total = table.totals().get(column.key());
                if (total != null) {
                    totals.add(format(column, total, locale));
                } else if (!labelled) {
                    totals.add(messages.t("m8.print.total", locale));
                    labelled = true;
                } else {
                    totals.add("");
                }
            }
            data.put("totals", totals);
        }
        return data;
    }

    private String format(Column column, String value, Locale locale) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return switch (column.kind()) {
            case ReportCatalogue.MONEY -> formats.money(new BigDecimal(value), locale);
            case ReportCatalogue.QTY -> formats.quantity(new BigDecimal(value), locale);
            case ReportCatalogue.DATE -> formats.date(LocalDate.parse(value));
            default -> value;
        };
    }

    private static boolean numeric(Column column) {
        return column.kind().equals(ReportCatalogue.MONEY) || column.kind().equals(ReportCatalogue.QTY);
    }
}
