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
        Map<String, Object> attributes,
        UUID departmentId) {

    public SkuView(
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
            Map<String, Object> attributes) {
        this(
                skuId,
                skuCode,
                ownerEntityId,
                status,
                nameEn,
                nameSi,
                nameTa,
                descriptionEn,
                descriptionSi,
                descriptionTa,
                baseUomCode,
                soldByWeight,
                batchTracked,
                expiryTracked,
                hasPrintedMrp,
                expiryWarningDays,
                taxCategoryId,
                multiMrpPolicy,
                originKind,
                attributes,
                null);
    }
}
