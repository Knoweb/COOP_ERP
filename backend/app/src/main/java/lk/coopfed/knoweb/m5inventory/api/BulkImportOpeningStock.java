package lk.coopfed.knoweb.m5inventory.api;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Command to import opening stock data in bulk from a spreadsheet or row list for a given location (CAT-11, PGL-M2-06).
 *
 * <p>Supports {@code VALIDATE} mode (dry-run preview with row diagnostics and zero database writes)
 * and {@code COMMIT} mode (creates an opening balance in {@code DRAFT} status awaiting signatures).
 *
 * @param locationId destination onboarding location ID
 * @param mode VALIDATE or COMMIT
 * @param sheet raw spreadsheet (XLSX Base64 or CSV) content
 * @param rows pre-parsed or structured opening stock rows
 */
public record BulkImportOpeningStock(UUID locationId, String mode, String sheet, List<OpeningStockRow> rows) {

    public static final String MODE_VALIDATE = "VALIDATE";
    public static final String MODE_COMMIT = "COMMIT";

    public BulkImportOpeningStock {
        mode = mode == null || mode.isBlank() ? MODE_COMMIT : mode.trim().toUpperCase(Locale.ROOT);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public BulkImportOpeningStock(UUID locationId, String sheet, List<OpeningStockRow> rows) {
        this(locationId, MODE_COMMIT, sheet, rows);
    }

    public static BulkImportOpeningStock ofSheet(UUID locationId, String sheet) {
        return new BulkImportOpeningStock(locationId, MODE_COMMIT, sheet, List.of());
    }

    public static BulkImportOpeningStock ofSheet(UUID locationId, String mode, String sheet) {
        return new BulkImportOpeningStock(locationId, mode, sheet, List.of());
    }

    public static BulkImportOpeningStock ofRows(UUID locationId, List<OpeningStockRow> rows) {
        return new BulkImportOpeningStock(locationId, MODE_COMMIT, null, rows);
    }

    public static BulkImportOpeningStock ofRows(UUID locationId, String mode, List<OpeningStockRow> rows) {
        return new BulkImportOpeningStock(locationId, mode, null, rows);
    }
}
