package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Report returned after importing opening stock in bulk for a location (CAT-11, PGL-M2-06).
 *
 * <p>In {@code VALIDATE} mode, provides detailed row-level diagnostics, preview data, and total
 * valuation with zero database mutation. In {@code COMMIT} mode, converts valid rows into a
 * {@code DRAFT} opening balance awaiting officer signatures.
 *
 * @param importId unique identifier of this bulk import operation
 * @param openingBalanceId ID of the created opening balance document in DRAFT state
 * @param status status of the import: IMPORTED, VALID, REJECTED, or EMPTY
 * @param totalRows total rows parsed from the sheet/payload
 * @param importedLines lines successfully converted into opening balance lines
 * @param validRows count of valid lines
 * @param errorRows count of lines with errors
 * @param totalValuation sum of valid line extended costs
 * @param problems diagnostics encountered
 * @param previewRows preview lines for re-check
 */
public record BulkOpeningStockReport(
        UUID importId,
        UUID openingBalanceId,
        String status,
        int totalRows,
        int importedLines,
        int validRows,
        int errorRows,
        BigDecimal totalValuation,
        List<OpeningStockProblem> problems,
        List<OpeningStockPreviewRow> previewRows) {

    public static final String IMPORTED = "IMPORTED";
    public static final String EMPTY = "EMPTY";
    public static final String REJECTED = "REJECTED";
    public static final String VALID = "VALID";

    public BulkOpeningStockReport {
        problems = problems == null ? List.of() : List.copyOf(problems);
        previewRows = previewRows == null ? List.of() : List.copyOf(previewRows);
        totalValuation = totalValuation == null ? BigDecimal.ZERO : totalValuation;
    }

    public BulkOpeningStockReport(
            UUID importId,
            UUID openingBalanceId,
            String status,
            int totalRows,
            int importedLines,
            List<OpeningStockProblem> problems) {
        this(
                importId,
                openingBalanceId,
                status,
                totalRows,
                importedLines,
                importedLines,
                problems == null ? 0 : problems.size(),
                BigDecimal.ZERO,
                problems,
                List.of());
    }
}
