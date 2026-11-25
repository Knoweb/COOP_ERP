package lk.coopfed.knoweb.m2catalogue.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A single raw or parsed row in a catalogue bulk import sheet (CAT-01).
 */
public record ImportSkuRow(
        int rowNumber,
        String skuCode,
        String nameEn,
        String nameSi,
        String nameTa,
        String descriptionEn,
        String descriptionSi,
        String descriptionTa,
        String baseUomCode,
        Boolean soldByWeight,
        Boolean batchTracked,
        Boolean expiryTracked,
        Boolean hasPrintedMrp,
        Short expiryWarningDays,
        String taxCategoryCode,
        BigDecimal price,
        List<String> barcodes,
        String brand,
        List<String> tags,
        Map<String, Object> attributes) {}
