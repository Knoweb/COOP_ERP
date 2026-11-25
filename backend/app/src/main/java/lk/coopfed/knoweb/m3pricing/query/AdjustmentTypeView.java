package lk.coopfed.knoweb.m3pricing.query;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A registered pricing/order adjustment type (doc 18 §4.8; doc 23 §3.2).
 *
 * @param code unique identifier code (e.g. SPECIAL_DISC, DELIVERY_FEE)
 * @param nameEn English display name
 * @param kind the adjustment kind (SPECIAL_DISCOUNT, DELIVERY_FEE)
 * @param permissionCode the permission required to record adjustments of this type
 * @param maxPercent maximum discount percentage if percentage-bounded (nullable)
 * @param maxAmount maximum amount in rupees if amount-bounded (nullable)
 * @param status ACTIVE or INACTIVE
 * @param ownerEntityId owning Federation entity
 */
public record AdjustmentTypeView(
        String code,
        String nameEn,
        AdjustmentKind kind,
        String permissionCode,
        BigDecimal maxPercent,
        BigDecimal maxAmount,
        String status,
        UUID ownerEntityId) {}
