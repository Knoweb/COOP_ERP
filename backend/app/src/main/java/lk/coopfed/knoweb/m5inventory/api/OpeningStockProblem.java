package lk.coopfed.knoweb.m5inventory.api;

/**
 * Diagnostic problem encountered while processing a bulk stock import row.
 *
 * @param line row index (1-based)
 * @param sku identifier string of the SKU if present
 * @param field field name with the issue
 * @param message description of the issue or error key
 */
public record OpeningStockProblem(int line, String sku, String field, String message) {

    public OpeningStockProblem(int line, String sku, String message) {
        this(line, sku, null, message);
    }
}
