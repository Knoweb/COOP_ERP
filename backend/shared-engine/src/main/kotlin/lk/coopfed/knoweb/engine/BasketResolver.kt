package lk.coopfed.knoweb.engine

import java.math.RoundingMode
import java.time.LocalDate

/** Steps 1 to 7 of doc 23 section 3.5 for a whole receipt (23A section 6, BasketResolver). */
object BasketResolver {

    @JvmStatic
    fun resolve(basket: Basket, ix: PricingSnapshotIndex, date: LocalDate, tender: TenderKind): BasketResult {
        val lines = basket.lines
            .map { PriceResolver.resolveLine(it, ix, date) }
            .map { RuleEvaluator.applyLineRules(it, ix, date, ix.stackingPolicy) }
            .map { recheckCeiling(it, ix, date) }

        val bill = RuleEvaluator.applyBillRule(lines, ix, date, ix.stackingPolicy)
        val billDiscount = bill?.amount ?: Money.ZERO
        val taxed = decomposeTax(lines, billDiscount)

        val subtotal = taxed.filter { it.sellable }.fold(Money.ZERO) { sum, line -> sum + line.lineTotal }
        val total = subtotal - billDiscount
        val rounding = if (tender == TenderKind.CASH) Rounding.toRupee(total) else Money.ZERO
        return BasketResult(
            lines = taxed,
            billRuleId = bill?.ruleId,
            billDiscount = billDiscount,
            subtotal = subtotal,
            total = total + rounding,
            rounding = rounding,
            engineVersion = ENGINE_VERSION,
            snapshotVersion = ix.snapshotVersion
        )
    }

    /** Step 6: after rules no line exceeds the control term; a line above it is clamped. */
    private fun recheckCeiling(r: LineResult, ix: PricingSnapshotIndex, date: LocalDate): LineResult {
        if (!r.sellable) {
            return r
        }
        val control = ix.ceilingFor(r.skuId, r.input.uom, date) ?: return r
        if (r.unitPrice <= control.price) {
            return r
        }
        return r.copy(
            unitPrice = control.price,
            controlPriceApplied = control.price,
            capReason = CapReason.CONTROL_PRICE,
            lineTotal = control.price * r.input.qty
        )
    }

    /**
     * Step 7: the tax inside each line's total, after the line's share of the bill discount. The
     * share is pro rata to the line total; the last sellable line takes the remainder so that the
     * shares add up to the bill discount exactly.
     */
    private fun decomposeTax(lines: List<LineResult>, billDiscount: Money): List<LineResult> {
        val sellable = lines.filter { it.sellable }
        val subtotal = sellable.fold(Money.ZERO) { sum, line -> sum + line.lineTotal }
        var remaining = billDiscount
        var seen = 0
        return lines.map { line ->
            if (!line.sellable) {
                line
            } else {
                seen++
                val share = when {
                    billDiscount == Money.ZERO || subtotal == Money.ZERO -> Money.ZERO
                    seen == sellable.size -> remaining
                    else -> Money.rounded(
                        billDiscount.amount.multiply(line.lineTotal.amount)
                            .divide(subtotal.amount, 2, RoundingMode.HALF_UP)
                    ).min(remaining)
                }
                remaining -= share
                val taxable = (line.lineTotal - share).max(Money.ZERO)
                line.copy(taxAmount = Tax.inclusivePart(taxable, line.taxRatePercent))
            }
        }
    }
}
