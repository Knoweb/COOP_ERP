package lk.coopfed.knoweb.m8reporting.query;

import java.time.LocalDate;
import java.util.UUID;

/** A report's parameters: a period for the period reports, a location for the stock position. */
public record ReportParameters(LocalDate from, LocalDate to, UUID locationId) {}
