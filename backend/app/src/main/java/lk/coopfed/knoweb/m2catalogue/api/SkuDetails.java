package lk.coopfed.knoweb.m2catalogue.api;

import java.util.Map;
import java.util.UUID;

public record SkuDetails(
        String shortNameEn,
        String shortNameSi,
        String shortNameTa,
        String descriptionEn,
        String descriptionSi,
        String descriptionTa,
        String baseUomCode,
        boolean soldByWeight,
        boolean batchTracked,
        boolean expiryTracked,
        boolean hasPrintedMrp,
        Short expiryWarningDays,
        UUID taxCategoryId,
        String multiMrpPolicy,
        String originKind,
        Map<String, Object> attributes) {}
