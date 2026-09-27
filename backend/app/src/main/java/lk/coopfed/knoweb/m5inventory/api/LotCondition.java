package lk.coopfed.knoweb.m5inventory.api;

/**
 * The condition of a lot (doc 25 section 3.1): damaged units received on a GRN form a DAMAGED lot
 * beside the GOOD one, sellable only through a markdown rule or written off.
 */
public enum LotCondition {
    GOOD,
    DAMAGED
}
