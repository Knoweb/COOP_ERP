package lk.coopfed.knoweb.m2catalogue.internal.steward;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.m2catalogue.api.ImportReport.ValidationError;
import lk.coopfed.knoweb.m2catalogue.api.ImportSkuRow;
import org.springframework.stereotype.Component;

/**
 * Validates catalogue import rows for mandatory fields, valid units, tax categories,
 * pricing, and barcode formats (CAT-01, 22A section 6.2, PGL-M2-05).
 *
 * <p>Supports idempotent re-import: genuinely identical existing SKUs are permitted
 * without code collision errors, while conflicting attributes or colliding barcodes are rejected.
 */
@Component
public class RowValidator {

    private static final int MAX_NAME_LENGTH = 80;
    private static final int MAX_CODE_LENGTH = 12;
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final Pattern BARCODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,64}$");

    public record ExistingSkuDetails(
            UUID skuId,
            String skuCode,
            String nameEn,
            String baseUom,
            UUID taxCategoryId,
            boolean soldByWeight,
            boolean batchTracked,
            boolean expiryTracked,
            boolean hasPrintedMrp,
            Set<String> barcodes) {

        public ExistingSkuDetails(
                UUID skuId, String skuCode, String nameEn, String baseUom, UUID taxCategoryId, Set<String> barcodes) {
            this(skuId, skuCode, nameEn, baseUom, taxCategoryId, false, false, false, true, barcodes);
        }
    }

    public record ValidationContext(
            Set<String> validUoms,
            Map<String, UUID> taxCategoriesByCode,
            Set<String> existingSkuCodes,
            Set<String> existingBarcodes,
            Map<String, ExistingSkuDetails> existingSkusByCode) {

        public ValidationContext(
                Set<String> validUoms,
                Map<String, UUID> taxCategoriesByCode,
                Set<String> existingSkuCodes,
                Set<String> existingBarcodes) {
            this(validUoms, taxCategoriesByCode, existingSkuCodes, existingBarcodes, Map.of());
        }
    }

    /**
     * Validates an entire batch of import rows, detecting both per-row field errors and
     * inter-row collisions within the sheet.
     */
    public List<ValidationError> validateBatch(List<ImportSkuRow> rows, ValidationContext ctx) {
        List<ValidationError> errors = new ArrayList<>();
        Set<String> seenCodesInSheet = new HashSet<>();
        Set<String> seenBarcodesInSheet = new HashSet<>();

        for (ImportSkuRow row : rows) {
            validateRow(row, ctx, seenCodesInSheet, seenBarcodesInSheet, errors);
        }

        return errors;
    }

    /**
     * Validates a single import row.
     */
    public void validateRow(
            ImportSkuRow row,
            ValidationContext ctx,
            Set<String> seenCodesInSheet,
            Set<String> seenBarcodesInSheet,
            List<ValidationError> errors) {

        int rowNum = row.rowNumber();

        // 1. English name
        if (row.nameEn() == null || row.nameEn().isBlank()) {
            errors.add(
                    new ValidationError(rowNum, "nameEn", "bulk.sku.name_en_required", "English name is required", ""));
        } else if (row.nameEn().strip().length() > MAX_NAME_LENGTH) {
            errors.add(new ValidationError(
                    rowNum,
                    "nameEn",
                    "bulk.sku.name_en_too_long",
                    "English name exceeds max length",
                    "max=" + MAX_NAME_LENGTH));
        }

        // 2. Base UOM
        String uom = null;
        if (row.baseUomCode() == null || row.baseUomCode().isBlank()) {
            errors.add(new ValidationError(
                    rowNum, "baseUomCode", "bulk.sku.base_uom_required", "Base UOM is required", ""));
        } else {
            uom = row.baseUomCode().strip().toUpperCase(Locale.ROOT);
            if (ctx.validUoms() != null && !ctx.validUoms().contains(uom)) {
                errors.add(new ValidationError(
                        rowNum, "baseUomCode", "bulk.sku.base_uom_unknown", "Unknown unit of measure", "uom=" + uom));
            }
        }

        // 3. Sold by weight
        boolean weight = Boolean.TRUE.equals(row.soldByWeight());
        if (weight && uom != null && !"KG".equals(uom)) {
            errors.add(new ValidationError(
                    rowNum, "soldByWeight", "bulk.sku.weight_requires_kg", "Sold by weight requires base UOM KG", ""));
        }

        // 4. Expiry tracked requires batch tracked
        boolean expiry = Boolean.TRUE.equals(row.expiryTracked());
        boolean batch = Boolean.TRUE.equals(row.batchTracked());
        if (expiry && !batch) {
            errors.add(new ValidationError(
                    rowNum,
                    "expiryTracked",
                    "bulk.sku.expiry_requires_batch",
                    "Expiry tracking requires batch tracking",
                    ""));
        }

        // 5. Tax category
        if (row.taxCategoryCode() == null || row.taxCategoryCode().isBlank()) {
            errors.add(new ValidationError(
                    rowNum, "taxCategoryCode", "bulk.sku.tax_category_required", "Tax category is required", ""));
        } else {
            String taxCode = row.taxCategoryCode().strip().toUpperCase(Locale.ROOT);
            if (ctx.taxCategoriesByCode() != null && !ctx.taxCategoriesByCode().containsKey(taxCode)) {
                errors.add(new ValidationError(
                        rowNum,
                        "taxCategoryCode",
                        "bulk.sku.tax_category_unknown",
                        "Unknown tax category",
                        "tax=" + taxCode));
            }
        }

        // 6. Price / MRP validation
        if (row.price() != null && row.price().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add(new ValidationError(
                    rowNum,
                    "price",
                    "bulk.sku.price_invalid",
                    "Price must be strictly positive",
                    "price=" + row.price()));
        }

        // 7. Optional SKU Code
        if (row.skuCode() != null && !row.skuCode().isBlank()) {
            String code = row.skuCode().strip();
            if (code.length() > MAX_CODE_LENGTH || !CODE_PATTERN.matcher(code).matches()) {
                errors.add(new ValidationError(
                        rowNum, "skuCode", "bulk.sku.code_invalid", "SKU code format invalid", "code=" + code));
            } else if (seenCodesInSheet != null && !seenCodesInSheet.add(code.toLowerCase(Locale.ROOT))) {
                errors.add(new ValidationError(
                        rowNum,
                        "skuCode",
                        "bulk.sku.code_duplicate_in_sheet",
                        "Duplicate SKU code in payload",
                        "code=" + code));
            } else if (ctx.existingSkusByCode() != null
                    && !ctx.existingSkusByCode().isEmpty()) {
                ExistingSkuDetails existing = ctx.existingSkusByCode().get(code.toLowerCase(Locale.ROOT));
                if (existing != null) {
                    // Check if genuinely identical or conflicting
                    String reqName = row.nameEn() != null ? row.nameEn().strip() : "";
                    String reqUom = row.baseUomCode() != null
                            ? row.baseUomCode().strip().toUpperCase(Locale.ROOT)
                            : "";
                    boolean namesMatch = reqName.equalsIgnoreCase(
                            existing.nameEn() != null ? existing.nameEn().strip() : "");
                    boolean uomsMatch = reqUom.equalsIgnoreCase(
                            existing.baseUom() != null ? existing.baseUom().strip() : "");

                    boolean taxCategoryMatches = true;
                    if (row.taxCategoryCode() != null && !row.taxCategoryCode().isBlank()) {
                        String reqTaxCode = row.taxCategoryCode().strip().toUpperCase(Locale.ROOT);
                        UUID reqTaxId = ctx.taxCategoriesByCode() != null
                                ? ctx.taxCategoriesByCode().get(reqTaxCode)
                                : null;
                        if (reqTaxId != null
                                && existing.taxCategoryId() != null
                                && !reqTaxId.equals(existing.taxCategoryId())) {
                            taxCategoryMatches = false;
                        }
                    }

                    boolean trackingFlagsMatch = true;
                    if (row.soldByWeight() != null && row.soldByWeight() != existing.soldByWeight()) {
                        trackingFlagsMatch = false;
                    }
                    if (row.batchTracked() != null && row.batchTracked() != existing.batchTracked()) {
                        trackingFlagsMatch = false;
                    }
                    if (row.expiryTracked() != null && row.expiryTracked() != existing.expiryTracked()) {
                        trackingFlagsMatch = false;
                    }
                    if (row.hasPrintedMrp() != null && row.hasPrintedMrp() != existing.hasPrintedMrp()) {
                        trackingFlagsMatch = false;
                    }

                    if (!namesMatch || !uomsMatch || !taxCategoryMatches || !trackingFlagsMatch) {
                        errors.add(new ValidationError(
                                rowNum,
                                "skuCode",
                                "m2.sku.code_conflict",
                                "SKU code already exists with conflicting attributes (existing: " + existing.nameEn()
                                        + " / " + existing.baseUom() + ")",
                                "code=" + code));
                    }
                }
            } else if (ctx.existingSkuCodes() != null && ctx.existingSkuCodes().contains(code)) {
                errors.add(new ValidationError(
                        rowNum,
                        "skuCode",
                        "bulk.sku.code_already_registered",
                        "SKU code already exists in catalogue",
                        "code=" + code));
            }
        }

        // 8. Barcodes
        if (row.barcodes() != null) {
            Set<String> seenBarcodesInRow = new HashSet<>();
            for (String rawBarcode : row.barcodes()) {
                if (rawBarcode == null || rawBarcode.isBlank()) continue;
                String barcode = rawBarcode.strip();

                if (!BARCODE_PATTERN.matcher(barcode).matches()) {
                    errors.add(new ValidationError(
                            rowNum,
                            "barcodes",
                            "bulk.sku.barcode_invalid",
                            "Invalid barcode format",
                            "barcode=" + barcode));
                } else if (isGtin(barcode) && !isValidGtinCheckDigit(barcode)) {
                    errors.add(new ValidationError(
                            rowNum,
                            "barcodes",
                            "bulk.sku.barcode_check_digit_invalid",
                            "GTIN check digit invalid",
                            "barcode=" + barcode));
                } else if (!seenBarcodesInRow.add(barcode)) {
                    errors.add(new ValidationError(
                            rowNum,
                            "barcodes",
                            "bulk.sku.barcode_duplicate_in_row",
                            "Duplicate barcode in item",
                            "barcode=" + barcode));
                } else if (seenBarcodesInSheet != null && !seenBarcodesInSheet.add(barcode)) {
                    errors.add(new ValidationError(
                            rowNum,
                            "barcodes",
                            "m2.barcode.duplicate_in_sheet",
                            "The barcode is duplicated within the import sheet",
                            "barcode=" + barcode));
                } else if (ctx.existingSkusByCode() != null
                        && !ctx.existingSkusByCode().isEmpty()) {
                    String code = row.skuCode() != null ? row.skuCode().strip().toLowerCase(Locale.ROOT) : null;
                    for (ExistingSkuDetails otherSku : ctx.existingSkusByCode().values()) {
                        if (otherSku.barcodes() != null && otherSku.barcodes().contains(barcode)) {
                            if (code == null || !code.equalsIgnoreCase(otherSku.skuCode())) {
                                errors.add(new ValidationError(
                                        rowNum,
                                        "barcodes",
                                        "m2.sku.barcode_conflict",
                                        "Barcode " + barcode + " is already registered to SKU " + otherSku.skuCode(),
                                        "barcode=" + barcode));
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean isGtin(String barcode) {
        int len = barcode.length();
        return (len == 8 || len == 12 || len == 13 || len == 14)
                && barcode.chars().allMatch(Character::isDigit);
    }

    /**
     * Standard GS1 Modulo-10 check digit calculation.
     */
    public static boolean isValidGtinCheckDigit(String barcode) {
        if (!isGtin(barcode)) return true;
        int len = barcode.length();
        int sum = 0;
        int multiplier = 3;

        // Traverse right-to-left excluding check digit
        for (int i = len - 2; i >= 0; i--) {
            int digit = barcode.charAt(i) - '0';
            sum += digit * multiplier;
            multiplier = (multiplier == 3) ? 1 : 3;
        }

        int expectedCheckDigit = (10 - (sum % 10)) % 10;
        int actualCheckDigit = barcode.charAt(len - 1) - '0';
        return expectedCheckDigit == actualCheckDigit;
    }
}
