package lk.coopfed.knoweb.m8reporting.api;

import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * A report's rows were taken away as a CSV file (wave 2, M8-11): which report, with which
 * parameters, and how many rows; never their content.
 *
 * @param exportId         this export, the subject of its audit record
 * @param reportId         the report definition
 * @param from             the first day of the period, for a report over a period
 * @param to               the last day of the period
 * @param reportLocationId the location the report was narrowed to; none for all
 * @param rowCount         how many rows the file holds
 */
public record ReportExported(
        UUID exportId, String reportId, LocalDate from, LocalDate to, UUID reportLocationId, int rowCount)
        implements DomainEvent {

    public static final String TYPE = "report.exported.v1";
}
