package lk.coopfed.knoweb.m2catalogue.api;

import java.util.List;

/**
 * Outcome of a supplier bulk import or validation run (PGL-M2-05).
 *
 * @param status {@code VALIDATED}, {@code ACCEPTED}, or {@code REJECTED}
 * @param totalRows total rows in source sheet
 * @param createdCount genuinely new suppliers created (or to be created)
 * @param skippedCount identical existing suppliers skipped without duplication
 * @param rejectedCount rows with validation or conflict errors
 * @param errors list of actionable row-level errors
 */
public record SupplierImportReport(
        String status,
        int totalRows,
        int createdCount,
        int skippedCount,
        int rejectedCount,
        List<SupplierValidationError> errors) {

    public static final String STATUS_VALIDATED = "VALIDATED";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REJECTED = "REJECTED";

    public SupplierImportReport {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public record SupplierValidationError(
            int rowNumber, String field, String code, String message, String rejectedValue) {}
}
