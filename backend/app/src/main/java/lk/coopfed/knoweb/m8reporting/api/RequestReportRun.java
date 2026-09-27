package lk.coopfed.knoweb.m8reporting.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Print a report to an A4 PDF (28A section 5, "reports/{id}/runs post requestRun"). The run is
 * recorded REQUESTED here and rendered on the worker, where Chromium runs (19A section 6); the
 * caller asks for the run until it is READY and then downloads it.
 *
 * @param reportId   one of the report definitions (ReportingQueries.reportIds)
 * @param from       the first day of the period, for a report over a period
 * @param to         the last day of the period
 * @param locationId one location of the caller's entity, for a report that takes one; none for all
 * @param language   en, si or ta: the language of the printed labels
 */
public record RequestReportRun(String reportId, LocalDate from, LocalDate to, UUID locationId, String language) {}
