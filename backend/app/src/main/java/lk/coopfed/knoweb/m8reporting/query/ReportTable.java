package lk.coopfed.knoweb.m8reporting.query;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A report's rows (28A section 5: "reports/{id}/data -> { columns, rows, freshness,
 * generatedAt }"). Values are plain text: names in the caller's language, dates ISO, amounts and
 * quantities as the database holds them (the client and the PDF format them, never round).
 *
 * @param columns   the columns, in order
 * @param rows      each row a map from column key to value; a missing key is an empty cell
 * @param totals    the sums of the QTY and MONEY columns, by key
 * @param freshness the latest event the report's projections applied for the caller, if any
 */
public record ReportTable(
        String reportId,
        String titleId,
        List<Column> columns,
        List<Map<String, String>> rows,
        Map<String, String> totals,
        Instant freshness,
        Instant generatedAt) {

    /**
     * @param key     the key of the column in a row
     * @param labelId the message id of the header
     * @param kind    TEXT, DATE, QTY or MONEY
     */
    public record Column(String key, String labelId, String kind) {}
}
