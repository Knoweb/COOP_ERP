package lk.coopfed.knoweb.engine

import java.time.LocalDate
import java.util.UUID

/**
 * The trade price of an order line (doc 23 sections 3.2 and 5.2, ResolveTradePrice): among the
 * lines of the relationship's TRADE list for the SKU and unit in force on the date, the one
 * whose tier is the highest not exceeding the quantity. Tier 0 is the base price. The quantity
 * is the ordered quantity (doc 10 A-03, doc 24 DR-2). Trade prices are tax-exclusive; M4 adds
 * the tax on the invoice.
 */
object TradePriceResolver {

    /** Null when the list has no line for the SKU and unit in force on the date. */
    @JvmStatic
    fun resolve(lines: List<TradeLine>, skuId: UUID, uom: String, qty: Quantity, date: LocalDate): TradeQuote? =
        lines
            .filter { it.skuId == skuId && it.uom == uom && it.inForce(date) && it.tierFromQty <= qty }
            .maxByOrNull { it.tierFromQty }
            ?.let { TradeQuote(it.lineId, it.skuId, it.uom, it.tierFromQty, it.price, ENGINE_VERSION) }

    /**
     * The tiers of one SKU and unit must start at 0 and rise (doc 23 section 6.2: "descending
     * tiers refused"). Empty when they do; otherwise the message id of the fault.
     */
    @JvmStatic
    fun tierFault(tiers: List<Quantity>): String? {
        if (tiers.isEmpty()) {
            return null
        }
        if (tiers.first() != Quantity.ZERO) {
            return "m3.price_list.line.tier_base_missing"
        }
        for (i in 1 until tiers.size) {
            if (tiers[i] <= tiers[i - 1]) {
                return "m3.price_list.line.tiers_not_ascending"
            }
        }
        return null
    }
}
