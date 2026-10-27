package lk.coopfed.knoweb.m5inventory.api;

import java.util.List;
import java.util.UUID;

/**
 * Command to import opening stock data in bulk from a spreadsheet or row list for a given location (CAT-11).
 *
 * <p>Converts the input into an opening balance in {@code DRAFT} status awaiting signatures.
 *
 * @param locationId destination onboarding location ID
 * @param sheet raw spreadsheet (CSV) content
 * @param rows pre-parsed or structured opening stock rows
 */
public record BulkImportOpeningStock(UUID locationId, String sheet, List<OpeningStockRow> rows) {

    public BulkImportOpeningStock {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public static BulkImportOpeningStock ofSheet(UUID locationId, String sheet) {
        return new BulkImportOpeningStock(locationId, sheet, List.of());
    }

    public static BulkImportOpeningStock ofRows(UUID locationId, List<OpeningStockRow> rows) {
        return new BulkImportOpeningStock(locationId, null, rows);
    }
}
