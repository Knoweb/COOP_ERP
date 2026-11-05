package lk.coopfed.knoweb.m3pricing.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The predicate of a discount rule in the closed vocabulary of doc 23 section 3.3: which item,
 * in which unit, from which quantity, how close to expiry, from which bill total, optional
 * customer class targeting (PGL-M1-06), and optional location/branch scope (PGL-M1-06).
 *
 * @param skuId the item a line rule applies to
 * @param uomCode the unit a TIME_LIMITED_PRICE applies to; null for every unit
 * @param minQty QUANTITY_BREAK: the quantity from which the benefit applies
 * @param daysToExpiry EXPIRY_MARKDOWN: the benefit applies to a batch this many days or fewer from expiry
 * @param billTotalFrom BILL_THRESHOLD: the bill total from which the benefit applies
 * @param customerClass optional customer class (MEMBER, EMPLOYEE, or null for general public)
 * @param locationId optional shop/branch location ID; null applies society-wide to all locations
 */
public record RulePredicate(
        UUID skuId,
        String uomCode,
        BigDecimal minQty,
        Integer daysToExpiry,
        BigDecimal billTotalFrom,
        String customerClass,
        UUID locationId) {

    public RulePredicate(
            UUID skuId, String uomCode, BigDecimal minQty, Integer daysToExpiry, BigDecimal billTotalFrom) {
        this(skuId, uomCode, minQty, daysToExpiry, billTotalFrom, null, null);
    }

    public RulePredicate(
            UUID skuId,
            String uomCode,
            BigDecimal minQty,
            Integer daysToExpiry,
            BigDecimal billTotalFrom,
            String customerClass) {
        this(skuId, uomCode, minQty, daysToExpiry, billTotalFrom, customerClass, null);
    }
}
