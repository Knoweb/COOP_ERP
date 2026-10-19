package lk.coopfed.knoweb.m2catalogue.internal.steward;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.m2catalogue.api.ImportReport.ValidationError;
import lk.coopfed.knoweb.m2catalogue.api.ImportSkuRow;
import lk.coopfed.knoweb.m2catalogue.internal.steward.RowValidator.ValidationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RowValidatorTest {

    private RowValidator validator;
    private ValidationContext ctx;

    @BeforeEach
    void setUp() {
        validator = new RowValidator();
        ctx = new ValidationContext(
                Set.of("EA", "KG", "G", "L", "ML", "PKT"),
                Map.of("STD", UUID.randomUUID(), "ZERO", UUID.randomUUID()),
                Set.of("SKU-EXISTING"),
                Set.of("4791000000096"));
    }

    @Test
    void validRowPassesValidation() {
        ImportSkuRow row = new ImportSkuRow(
                1,
                "SKU-001",
                "White Sugar 1kg",
                "සුදු සීනි",
                "வெள்ளை சர்க்கரை",
                null,
                null,
                null,
                "KG",
                true,
                false,
                false,
                false,
                null,
                "STD",
                new BigDecimal("320.00"),
                List.of("4791000000003"),
                "cargills",
                List.of("sugar"),
                null);

        List<ValidationError> errors = validator.validateBatch(List.of(row), ctx);
        assertThat(errors).isEmpty();
    }

    @Test
    void rejectsMissingNameEn() {
        ImportSkuRow row = new ImportSkuRow(
                1, "SKU-001", "  ", null, null, null, null, null, "EA", false, false, false, false, null, "STD", null,
                null, null, null, null);

        List<ValidationError> errors = validator.validateBatch(List.of(row), ctx);
        assertThat(errors).anyMatch(e -> "bulk.sku.name_en_required".equals(e.code()));
    }

    @Test
    void rejectsSoldByWeightWithoutKilogramBaseUnit() {
        ImportSkuRow row = new ImportSkuRow(
                1,
                "SKU-001",
                "Basmati Rice",
                null,
                null,
                null,
                null,
                null,
                "G",
                true,
                false,
                false,
                false,
                null,
                "STD",
                null,
                null,
                null,
                null,
                null);

        List<ValidationError> errors = validator.validateBatch(List.of(row), ctx);
        assertThat(errors).anyMatch(e -> "bulk.sku.weight_requires_kg".equals(e.code()));
    }

    @Test
    void rejectsExpiryTrackingWithoutBatchTracking() {
        ImportSkuRow row = new ImportSkuRow(
                1,
                "SKU-001",
                "Fresh Milk 1L",
                null,
                null,
                null,
                null,
                null,
                "L",
                false,
                false,
                true,
                false,
                null,
                "STD",
                null,
                null,
                null,
                null,
                null);

        List<ValidationError> errors = validator.validateBatch(List.of(row), ctx);
        assertThat(errors).anyMatch(e -> "bulk.sku.expiry_requires_batch".equals(e.code()));
    }

    @Test
    void rejectsInvalidGtinBarcodeCheckDigit() {
        // 4791000000003 is valid GTIN-13, 4791000000009 has invalid check digit
        ImportSkuRow row = new ImportSkuRow(
                1,
                "SKU-001",
                "Test Item",
                null,
                null,
                null,
                null,
                null,
                "EA",
                false,
                false,
                false,
                false,
                null,
                "STD",
                null,
                List.of("4791000000009"),
                null,
                null,
                null);

        List<ValidationError> errors = validator.validateBatch(List.of(row), ctx);
        assertThat(errors).anyMatch(e -> "bulk.sku.barcode_check_digit_invalid".equals(e.code()));
    }

    @Test
    void rejectsDuplicateBarcodesWithinRow() {
        ImportSkuRow row = new ImportSkuRow(
                1,
                "SKU-001",
                "Item A",
                null,
                null,
                null,
                null,
                null,
                "EA",
                false,
                false,
                false,
                false,
                null,
                "STD",
                null,
                List.of("4791000000003", "4791000000003"),
                null,
                null,
                null);

        List<ValidationError> errors = validator.validateBatch(List.of(row), ctx);
        assertThat(errors).anyMatch(e -> "bulk.sku.barcode_duplicate_in_row".equals(e.code()));
    }

    @Test
    void rejectsDuplicateSkuCodeInCatalogue() {
        ImportSkuRow row = new ImportSkuRow(
                1,
                "SKU-EXISTING",
                "Existing Item",
                null,
                null,
                null,
                null,
                null,
                "EA",
                false,
                false,
                false,
                false,
                null,
                "STD",
                null,
                null,
                null,
                null,
                null);

        List<ValidationError> errors = validator.validateBatch(List.of(row), ctx);
        assertThat(errors).anyMatch(e -> "bulk.sku.code_already_registered".equals(e.code()));
    }
}
