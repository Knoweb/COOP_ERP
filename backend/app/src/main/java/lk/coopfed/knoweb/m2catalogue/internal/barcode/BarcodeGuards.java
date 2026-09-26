package lk.coopfed.knoweb.m2catalogue.internal.barcode;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.internal.barcode.BarcodeStore.BarcodeRow;

/** The guards the three barcode handlers share (22A section 6; doc 22 sections 3.3 and 4.2). */
final class BarcodeGuards {

    static final String ACTIVE = "ACTIVE";
    static final String RETIRED = "RETIRED";
    static final String INTERNAL = "INTERNAL";

    /** The symbologies of catalogue.sku_barcode (22A section 3). */
    static final Set<String> SYMBOLOGIES =
            Set.of("EAN13", "EAN8", "UPCA", "GS1_128", "GS1_DATAMATRIX", "GS1_QR", INTERNAL);

    /** The symbologies that are a bare GTIN with a check digit, and its length. */
    private static final Map<String, Integer> GTIN_LENGTH = Map.of("EAN13", 13, "EAN8", 8, "UPCA", 12);

    private BarcodeGuards() {}

    static String requiredBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "barcode"));
        }
        return barcode.strip();
    }

    static String requiredSymbology(String symbology) {
        if (symbology == null || symbology.isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "symbology"));
        }
        String value = symbology.strip().toUpperCase();
        if (!SYMBOLOGIES.contains(value)) {
            throw new ProblemException("m2.barcode.symbology_invalid", Map.of("symbology", value));
        }
        return value;
    }

    /** 22A section 6: "check digit valid for EAN/UPC". A GS1 element string or an INTERNAL code is free text. */
    static void requireCheckDigit(String barcode, String symbology) {
        Integer length = GTIN_LENGTH.get(symbology);
        if (length == null) {
            return;
        }
        if (barcode.length() != length || !GtinParser.allDigits(barcode)) {
            throw new ProblemException("m2.barcode.format_invalid", Map.of("symbology", symbology, "length", length));
        }
        if (!GtinParser.isValidGtin(barcode)) {
            throw new ProblemException("m2.barcode.check_digit_invalid", Map.of("barcode", barcode));
        }
    }

    /**
     * The caller's own ACTIVE row of the code (22A section 6, RetireBarcode and
     * LinkBarcodeToBatch: "owner"). The row is looked up by the caller's entity, so another
     * entity's row of the same code is simply not found: a society cannot retire the
     * Federation's factory barcode, nor another society's INTERNAL sticker.
     */
    static BarcodeRow requireOwnActive(
            BarcodeStore store, UUID skuId, String barcode, String symbology, ScopeContext scope) {
        BarcodeRow row = store.findOwn(barcode, symbology, scope.entityId())
                .filter(found -> found.skuId().equals(skuId))
                .orElseThrow(() -> new ProblemException("m2.barcode.not_found", Map.of("barcode", barcode)));

        if (!row.active()) {
            throw new ProblemException("m2.barcode.not_active", Map.of("barcode", barcode));
        }

        return row;
    }

    /** 22A section 6, LinkBarcodeToBatch: "batch belongs to sku". */
    static void requireBatchOfSku(BarcodeStore store, UUID batchId, UUID skuId) {
        if (batchId == null) {
            throw new ProblemException("request.field.required", Map.of("field", "batchId"));
        }
        if (!store.batchBelongsToSku(batchId, skuId)) {
            throw new ProblemException("m2.barcode.batch_mismatch", Map.of("batchId", batchId));
        }
    }
}
