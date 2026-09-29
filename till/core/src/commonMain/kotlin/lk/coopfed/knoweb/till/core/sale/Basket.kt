package lk.coopfed.knoweb.till.core.sale

import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.money.Qty
import lk.coopfed.knoweb.till.core.snapshot.Item

/** A line rung up at the counter: the item, how many, and the price the till charges. */
data class BasketLine(val item: Item, val qty: Qty, val unitPrice: Money) {
    val total: Money get() = qty.times(unitPrice)
}

/**
 * The sale in progress. Scanning the same item again adds one to its line; the till never
 * reprices a line the cashier has seen.
 */
class Basket {
    private val entries = mutableListOf<BasketLine>()

    val lines: List<BasketLine> get() = entries.toList()
    val total: Money get() = entries.fold(Money.ZERO) { sum, line -> sum + line.total }
    val isEmpty: Boolean get() = entries.isEmpty()

    fun add(item: Item, unitPrice: Money, qty: Qty = Qty.ONE) {
        require(qty.milli > 0) { "A quantity must be more than zero" }
        require(unitPrice.cents >= 0) { "A price cannot be negative" }
        val index = entries.indexOfFirst { it.item.skuId == item.skuId && it.unitPrice == unitPrice }
        if (index >= 0 && !item.soldByWeight) {
            entries[index] = entries[index].copy(qty = entries[index].qty + qty)
        } else {
            entries += BasketLine(item, qty, unitPrice)
        }
    }

    fun setQty(index: Int, qty: Qty) {
        require(qty.milli > 0) { "A quantity must be more than zero" }
        entries[index] = entries[index].copy(qty = qty)
    }

    fun remove(index: Int) {
        entries.removeAt(index)
    }

    fun clear() = entries.clear()
}

/** The cash the customer handed over must cover the total. */
class TenderTooSmall(val total: Money, val tendered: Money) : Exception("Cash ${tendered.display()} is less than ${total.display()}")
