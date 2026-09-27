package lk.coopfed.knoweb.m8reporting.query;

/**
 * A report definition as a caller sees it (28A section 3, report_definition: id, name,
 * decision supported).
 *
 * @param reportId   the id in the URL, stock-position
 * @param titleId    the message id of the report's name
 * @param decisionId the message id of the decision the report supports
 * @param period     whether the report takes a period (from, to)
 * @param location   whether the report takes a location
 */
public record ReportDefinitionView(
        String reportId, String titleId, String decisionId, boolean period, boolean location) {}
