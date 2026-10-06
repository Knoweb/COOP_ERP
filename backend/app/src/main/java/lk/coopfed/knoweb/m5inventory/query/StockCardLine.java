package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;

/**
 * One line of an item's stock card at a location (25A section 8, "Stock card"): a ledger movement
 * and the quantity of the item at the location, all lots and conditions, after it.
 */
public record StockCardLine(MovementView movement, BigDecimal balanceAfter) {}
