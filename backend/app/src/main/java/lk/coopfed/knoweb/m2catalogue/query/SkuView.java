package lk.coopfed.knoweb.m2catalogue.query;

import java.util.Map;
import java.util.UUID;

public record SkuView(
        UUID skuId,
        String skuCode,
        UUID ownerEntityId,
        String status,
        String nameEn,
        String nameSi,
        String nameTa,
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
