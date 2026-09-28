package lk.coopfed.knoweb.m3pricing.api;

import java.math.BigDecimal;

/**
 * The benefit of a discount rule (doc 23 section 3.3): FIXED_PRICE (the unit price becomes the
 * value), PERCENT_OFF (a percentage above 0 and at most 100), AMOUNT_OFF (rupees off per unit, or
 * off the bill for BILL_THRESHOLD). FREE_QTY belongs to FREE_ITEM, deferred for the demo.
 */
public record RuleBenefit(String kind, BigDecimal value) {}
