package lk.coopfed.knoweb.m3pricing.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The predicate of a discount rule in the closed vocabulary of doc 23 section 3.3: which item,
 * in which unit, from which quantity, how close to expiry, from which bill total. Which fields a
 * kind needs, allows or forbids is RuleVocabulary's to check; a field a kind does not use is null.
 * A tag in place of an item (doc 23 "sku or tag") is deferred for the demo: M2 publishes no tag
 * query to check it against.
 *
 * @param skuId         the item a line rule applies to
 * @param uomCode       the unit a TIME_LIMITED_PRICE applies to; null for every unit
 * @param minQty        QUANTITY_BREAK: the quantity from which the benefit applies
 * @param daysToExpiry  EXPIRY_MARKDOWN: the benefit applies to a batch this many days or fewer from expiry
 * @param billTotalFrom BILL_THRESHOLD: the bill total from which the benefit applies
 */
public record RulePredicate(
        UUID skuId, String uomCode, BigDecimal minQty, Integer daysToExpiry, BigDecimal billTotalFrom) {}
