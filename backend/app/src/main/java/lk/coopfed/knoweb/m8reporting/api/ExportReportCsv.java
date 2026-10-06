package lk.coopfed.knoweb.m8reporting.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Take a report's rows away as a CSV file (28A section 6; wave 2, M8-11, decision D7): the rows
 * are read as the screen reads them, and the export is audited {@code REPORT_EXPORTED} with the
 * report, its parameters and the number of rows, never their content. A screen read is not
 * audited.
 *
 * @param reportId   one of the report definitions
 * @param from       the first day of the period, for a report over a period
 * @param to         the last day of the period
 * @param locationId one location of the caller's entity, for a report that takes one; none for all
 */
public record ExportReportCsv(String reportId, LocalDate from, LocalDate to, UUID locationId) {}
