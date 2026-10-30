package lk.coopfed.knoweb.m4trading.api;

import java.util.List;
import java.util.UUID;

/** Command to import inter-entity receivable opening balances in bulk (CAT-12). */
public record BulkImportReceivables(
        UUID sellerEntityId, String sheet, List<ReceivableOpeningRow> rows, boolean simulateSignatures) {

    public static BulkImportReceivables ofSheet(UUID sellerEntityId, String sheet) {
        return new BulkImportReceivables(sellerEntityId, sheet, List.of(), false);
    }

    public static BulkImportReceivables ofSheetWithSignatures(UUID sellerEntityId, String sheet) {
        return new BulkImportReceivables(sellerEntityId, sheet, List.of(), true);
    }

    public static BulkImportReceivables ofRows(UUID sellerEntityId, List<ReceivableOpeningRow> rows) {
        return new BulkImportReceivables(sellerEntityId, null, rows, false);
    }
}
