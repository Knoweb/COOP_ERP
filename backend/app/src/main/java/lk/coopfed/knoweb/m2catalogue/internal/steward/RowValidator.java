package lk.coopfed.knoweb.m2catalogue.internal.steward;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.m2catalogue.api.ImportReport.ValidationError;
import lk.coopfed.knoweb.m2catalogue.api.ImportSkuRow;
import org.springframework.stereotype.Component;

/**
 * Validates catalogue import rows for mandatory fields, valid units, tax categories,
 * pricing, and barcode formats (CAT-01, 22A section 6.2).
 */
@Component
public class RowValidator {

    private static final int MAX_NAME_LENGTH = 80;
    private static final int MAX_CODE_LENGTH = 12;
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final Pattern BARCODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,64}$");

    public record ValidationContext(
            Set<String> validUoms,
            Map<String, java.util.UUID> taxCategoriesByCode,
            Set<String> existingSkuCodes,
            Set<String> existingBarcodes) {}

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
