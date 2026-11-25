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

    @Test
    void allowsIdenticalSkuReimportWhenAttributesMatch() {
        UUID stdTaxId = UUID.randomUUID();
        ValidationContext richCtx = new ValidationContext(
                Set.of("EA", "KG"),
                Map.of("STD", stdTaxId),
                Set.of("SKU-001"),
                Set.of("4791000000003"),
                Map.of(
                        "sku-001",
                        new RowValidator.ExistingSkuDetails(
                                UUID.randomUUID(),
                                "SKU-001",
                                "White Sugar 1kg",
                                "KG",
                                stdTaxId,
                                false,
                                false,
                                false,
                                false,
                                Set.of("4791000000003"))));

        ImportSkuRow row = new ImportSkuRow(
                1,
                "SKU-001",
                "White Sugar 1kg",
                null,
                null,
                null,
                null,
                null,
                "KG",
                false,
                false,
                false,
                false,
                null,
                "STD",
                new BigDecimal("320.00"),
                List.of("4791000000003"),
                null,
                null,
                null);

        List<ValidationError> errors = validator.validateBatch(List.of(row), richCtx);
        assertThat(errors).isEmpty();
    }

    @Test
    void rejectsConflictingSkuCodeWhenAttributesDiffer() {
        ValidationContext richCtx = new ValidationContext(
                Set.of("EA", "KG"),
                Map.of("STD", UUID.randomUUID()),
                Set.of("SKU-001"),
                Set.of("4791000000003"),
                Map.of(
                        "sku-001",
                        new RowValidator.ExistingSkuDetails(
                                UUID.randomUUID(),
                                "SKU-001",
                                "White Sugar 1kg",
                                "KG",
                                UUID.randomUUID(),
                                Set.of("4791000000003"))));

        ImportSkuRow row = new ImportSkuRow(
                1,
                "SKU-001",
                "Brown Sugar 1kg",
                null,
                null,
                null,
                null,
                null,
                "KG",
                false,
                false,
                false,
                false,
                null,
                "STD",
                new BigDecimal("320.00"),
                null,
                null,
                null,
                null);

        List<ValidationError> errors = validator.validateBatch(List.of(row), richCtx);
        assertThat(errors).anyMatch(e -> "m2.sku.code_conflict".equals(e.code()));
    }

    @Test
    void rejectsBarcodeRegisteredToDifferentSku() {
        ValidationContext richCtx = new ValidationContext(
                Set.of("EA", "KG"),
                Map.of("STD", UUID.randomUUID()),
                Set.of("SKU-001"),
                Set.of("4791000000003"),
                Map.of(
                        "sku-001",
                        new RowValidator.ExistingSkuDetails(
                                UUID.randomUUID(),
                                "SKU-001",
                                "White Sugar 1kg",
                                "KG",
                                UUID.randomUUID(),
                                Set.of("4791000000003"))));

        ImportSkuRow row = new ImportSkuRow(
                1,
                "SKU-002",
                "New Flour 1kg",
                null,
                null,
                null,
                null,
                null,
                "KG",
                false,
                false,
                false,
                false,
                null,
                "STD",
                new BigDecimal("220.00"),
                List.of("4791000000003"),
                null,
                null,
                null);

        List<ValidationError> errors = validator.validateBatch(List.of(row), richCtx);
        assertThat(errors).anyMatch(e -> "m2.sku.barcode_conflict".equals(e.code()));
    }

    @Test
    void rejectsConflictingSkuTaxCategoryWhenCodeMatches() {
        UUID stdTaxId = UUID.randomUUID();
        UUID zeroTaxId = UUID.randomUUID();

        ValidationContext richCtx = new ValidationContext(
                Set.of("EA", "KG"),
                Map.of("STD", stdTaxId, "ZERO", zeroTaxId),
                Set.of("SKU-001"),
                Set.of("4791000000003"),
                Map.of(
                        "sku-001",
                        new RowValidator.ExistingSkuDetails(
                                UUID.randomUUID(),
                                "SKU-001",
                                "White Sugar 1kg",
                                "KG",
                                stdTaxId,
                                Set.of("4791000000003"))));

        ImportSkuRow row = new ImportSkuRow(
                1,
                "SKU-001",
                "White Sugar 1kg",
                null,
                null,
                null,
                null,
                null,
                "KG",
                false,
                false,
                false,
                false,
                null,
                "ZERO",
                new BigDecimal("320.00"),
                null,
                null,
                null,
                null);

        List<ValidationError> errors = validator.validateBatch(List.of(row), richCtx);
        assertThat(errors).anyMatch(e -> "m2.sku.code_conflict".equals(e.code()));
    }

    @Test
    void rejectsConflictingSkuTrackingFlagsWhenCodeMatches() {
        UUID taxId = UUID.randomUUID();

        ValidationContext richCtx = new ValidationContext(
                Set.of("EA", "KG"),
                Map.of("STD", taxId),
                Set.of("SKU-001"),
                Set.of("4791000000003"),
                Map.of(
                        "sku-001",
                        new RowValidator.ExistingSkuDetails(
                                UUID.randomUUID(),
                                "SKU-001",
                                "Fresh Milk 1L",
                                "EA",
                                taxId,
                                false,
                                false,
                                false,
                                true,
                                Set.of())));

        // Incoming row declares batchTracked=true, expiryTracked=true (conflicts with existing)
        ImportSkuRow row = new ImportSkuRow(
                1,
                "SKU-001",
                "Fresh Milk 1L",
                null,
                null,
                null,
                null,
                null,
                "EA",
                false,
                true,
                true,
                true,
                null,
                "STD",
                new BigDecimal("450.00"),
                null,
                null,
                null,
                null);

        List<ValidationError> errors = validator.validateBatch(List.of(row), richCtx);
        assertThat(errors).anyMatch(e -> "m2.sku.code_conflict".equals(e.code()));
    }

    @Test
    void allowsDuplicateBarcodeAcrossDifferentRowsInSheetForDuplicateSuspectScoring() {
        ImportSkuRow row1 = new ImportSkuRow(
                1,
                null,
                "Anchor Milk Powder 400g",
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
                new BigDecimal("1080.00"),
                List.of("4791000000003"),
                "anchor",
                null,
                null);

        ImportSkuRow row2 = new ImportSkuRow(
                2,
                null,
                "Anchor Full Cream Milk Powder 400g",
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
                new BigDecimal("1080.00"),
                List.of("4791000000003"),
                "anchor",
                null,
                null);

        List<ValidationError> errors = validator.validateBatch(List.of(row1, row2), ctx);
        assertThat(errors).isEmpty();
    }

    @Test
    void allowsNewSkuMatchingExistingBarcodeForDuplicateSuspectScoring() {
        ValidationContext richCtx = new ValidationContext(
                Set.of("EA", "KG"),
                Map.of("STD", UUID.randomUUID()),
                Set.of("SKU-001"),
                Set.of("4791000000003"),
                Map.of(
                        "sku-001",
                        new RowValidator.ExistingSkuDetails(
                                UUID.randomUUID(),
                                "SKU-001",
                                "Maliban Marie 300g",
                                "EA",
                                UUID.randomUUID(),
                                Set.of("4791000000003"))));

        // Incoming candidate SKU without an explicit code (new item) sharing barcode with existing SKU
        ImportSkuRow candidateRow = new ImportSkuRow(
                1,
                null,
                "Maliban Marie Biscuits 300g Pkt",
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
                List.of("4791000000003"),
                "maliban",
                null,
                null);

        List<ValidationError> errors = validator.validateBatch(List.of(candidateRow), richCtx);
        assertThat(errors).isEmpty();
    }
}
