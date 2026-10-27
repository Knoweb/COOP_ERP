package lk.coopfed.knoweb.m5inventory.api;

import java.util.List;
import java.util.UUID;

/**
 * Report returned after importing opening stock in bulk for a location (CAT-11).
 *
 * <p>The imported stock is not immediately active: it creates an opening balance in {@code DRAFT}
 * status awaiting required entity and Federation signatures.
 *
 * @param importId unique identifier of this bulk import operation
 * @param openingBalanceId ID of the created opening balance document in DRAFT state
 * @param status status of the import: IMPORTED, EMPTY, or REJECTED
 * @param totalRows total rows parsed from the sheet/payload
 * @param importedLines lines successfully converted into opening balance lines
 * @param problems any diagnostics encountered
 */
public record BulkOpeningStockReport(
        UUID importId,
        UUID openingBalanceId,
        String status,
        int totalRows,
        int importedLines,
        List<OpeningStockProblem> problems) {

    public static final String IMPORTED = "IMPORTED";
    public static final String EMPTY = "EMPTY";
    public static final String REJECTED = "REJECTED";

    public BulkOpeningStockReport {
        problems = problems == null ? List.of() : List.copyOf(problems);
    }
}
