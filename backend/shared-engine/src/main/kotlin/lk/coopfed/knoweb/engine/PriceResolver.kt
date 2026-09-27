package lk.coopfed.knoweb.engine

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Steps 1 to 4 of doc 23 section 3.5 (23A section 6): the list price, the batch term under the
 * multi-MRP policy, the control term, and the lowest of the three with the reason it was chosen.
 */
object PriceResolver {

    @JvmStatic
    fun resolveLine(input: LineInput, ix: PricingSnapshotIndex, date: LocalDate): LineResult {
        // 1. list price: no RETAIL line in force means the item is not sellable.
        val list = ix.retailLine(input.skuId, input.uom, date)
            ?: return LineResult.notSellable(input, LineResult.NO_LIST_LINE)
        val sku = ix.sku(input.skuId)
            ?: return LineResult.notSellable(input, LineResult.NO_LIST_LINE)
        val candidates = ix.inStockBatches(input.skuId).filter { it.onHand > Quantity.ZERO }
        val policy = ix.policyFor(input.skuId)

        // 2. batch term
        val scanned = input.scannedBatchId?.let(ix::batch)
        val picked = input.pickedBatchId?.let(ix::batch)
        val batch: BatchCandidate?
        val batchTerm: Money?
        val reasonIfBound: CapReason
        when {
            !sku.hasPrintedMrp -> {
                batch = scanned
                batchTerm = null
                reasonIfBound = CapReason.NONE
            }
            policy.kind == PolicyKind.BARCODE_RESOLVED && scanned != null -> {
                batch = scanned
                batchTerm = scanned.printedMrp
                reasonIfBound = CapReason.MRP_BARCODE
            }
            policy.kind == PolicyKind.PICKER && picked != null -> {
                batch = picked
                batchTerm = picked.printedMrp
                reasonIfBound = CapReason.MRP_PICKED
            }
            policy.kind == PolicyKind.PICKER && gapExceeds(candidates, policy) ->
                return LineResult.needsPick(input, candidates)
            else -> {
                batch = candidates.minByOrNull { it.printedMrp ?: Money.MAX }
                batchTerm = batch?.printedMrp
                reasonIfBound = CapReason.MRP_LOWEST
            }
        }

        // 3. control term: the lowest of the SKU's and its tags' ceilings in force.
        val control = ix.ceilingFor(input.skuId, input.uom, date)

        // 4. base unit price: min(list, batch term, control term), and which bound applied.
        var unit = list.price
        var reason = CapReason.NONE
        if (batchTerm != null && batchTerm < unit) {
            unit = batchTerm
            reason = reasonIfBound
        }
        if (control != null && control.price < unit) {
            unit = control.price
            reason = CapReason.CONTROL_PRICE
        }
        return LineResult(
            input = input,
            sellable = true,
            batch = batch,
            basePrice = unit,
            unitPrice = unit,
            mrpApplied = batchTerm,
            controlPriceApplied = control?.price,
            capReason = reason,
            taxRatePercent = sku.taxRatePercent,
            lineTotal = unit * input.qty
        )
    }

    /** The PICKER threshold (doc 23 DR-4, Rs 20 or 5 %): either one crossed shows the picker. */
    private fun gapExceeds(candidates: List<BatchCandidate>, policy: Policy): Boolean {
        val mrps = candidates.mapNotNull { it.printedMrp }
        if (mrps.size < 2) {
            return false
        }
        val low = mrps.min()
        val gap = mrps.max() - low
        val overAmount = policy.gapAmount != null && gap > policy.gapAmount
        val overPercent = policy.gapPercent != null &&
            low.amount.signum() > 0 &&
            gap.amount.multiply(BigDecimal(100)) > low.amount.multiply(policy.gapPercent)
        return overAmount || overPercent
    }
}
