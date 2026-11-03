package lk.coopfed.knoweb.m2catalogue.api;

import java.util.List;

/**
 * Command to bulk import or validate suppliers for the caller's entity (PGL-M2-05).
 *
 * @param mode {@code VALIDATE} for dry-run or {@code COMMIT} to persist
 * @param csvContent raw CSV content (optional if rows are supplied)
 * @param rows parsed supplier rows
 */
public record BulkImportSuppliers(String mode, String csvContent, List<SupplierImportRow> rows) {

    public static final String MODE_VALIDATE = "VALIDATE";
    public static final String MODE_COMMIT = "COMMIT";

    public BulkImportSuppliers {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
