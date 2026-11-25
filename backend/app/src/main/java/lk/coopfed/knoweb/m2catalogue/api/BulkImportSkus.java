package lk.coopfed.knoweb.m2catalogue.api;

import java.util.List;

/**
 * Command to validate or commit a bulk catalogue import of SKUs (CAT-01, 22A section 6.2).
 */
public record BulkImportSkus(String mode, List<ImportSkuRow> rows) {

    public static final String MODE_VALIDATE = "VALIDATE";
    public static final String MODE_COMMIT = "COMMIT";
}
