package lk.coopfed.knoweb.engine

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import lk.coopfed.knoweb.engine.Money
import lk.coopfed.knoweb.engine.Quantity

/*
 * The engine's model (23A section 6). Plain values: no clock, no I/O. Central builds them from
 * tables, the till from its snapshot, and both hand them to the same functions.
 */

/** A line of a RETAIL list in force: tax-inclusive (doc 23 section 3.1). */
data class RetailLine(
    val skuId: UUID,
    val uom: String,
    val price: Money,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate? = null,
    val tierFromQty: Quantity = Quantity.ZERO
) {
    fun inForce(date: LocalDate): Boolean =
        !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo))
}

/** A batch in stock at the location, with its printed MRP (null when the SKU has none). */
data class BatchCandidate(
    val batchId: UUID,
    val batchNo: String,
    val printedMrp: Money?,
    val expiry: LocalDate?,
    val onHand: Quantity
)

/** A control price in force, already in the line's unit (the index converts; 23A section 6). */
data class Ceiling(
    val price: Money,
    val uom: String,
    val ref: String,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate? = null
) {
    fun inForce(date: LocalDate): Boolean =
        !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo))
}

enum class PolicyKind { AUTO_LOWEST, BARCODE_RESOLVED, PICKER }

/** The multi-MRP policy of a SKU (doc 23 section 3.4); the gap applies to PICKER only. */
data class Policy(
    val kind: PolicyKind,
    val gapAmount: Money? = null,
    val gapPercent: BigDecimal? = null
) {
    companion object {
        @JvmField
        val DEFAULT = Policy(PolicyKind.AUTO_LOWEST)
    }
}

/** What the engine needs to know of a SKU (M2): printed MRP flag, tags, the tax rate in force. */
data class SkuFacts(
    val skuId: UUID,
    val hasPrintedMrp: Boolean,
    val taxRatePercent: BigDecimal,
    val tags: Set<String> = emptySet()
)

enum class CapReason { NONE, MRP_LOWEST, MRP_BARCODE, MRP_PICKED, CONTROL_PRICE }

enum class StackingPolicy { PRIORITY_THEN_BEST, BEST_ONLY }

enum class TenderKind { CASH, CARD, ACCOUNT }

enum class RuleKind { TIME_LIMITED_PRICE, QUANTITY_BREAK, BILL_THRESHOLD, FREE_ITEM, EXPIRY_MARKDOWN }

enum class BenefitKind { FIXED_PRICE, PERCENT_OFF, AMOUNT_OFF, FREE_QTY }

/**
 * A discount rule, its predicate and benefit already read from the JSON of doc 23 section 3.3
 * (the closed vocabulary: no expression language). Fields a kind does not use are null.
 */
data class Rule(
    val ruleId: UUID,
    val kind: RuleKind,
    val priority: Int,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val skuId: UUID? = null,
    val tag: String? = null,
    val uom: String? = null,
    val minQty: Quantity? = null,
    val daysToExpiry: Int? = null,
    val billThreshold: Money? = null,
    val benefitKind: BenefitKind,
    val benefitValue: BigDecimal
) {
    fun validOn(date: LocalDate): Boolean =
        !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo))

    val lineLevel: Boolean get() = kind != RuleKind.BILL_THRESHOLD
}

/** One line of a basket as the till scans it (23A section 6, LineInput). */
data class LineInput(
    val skuId: UUID,
    val uom: String,
    val qty: Quantity,
    val scannedBatchId: UUID? = null,
    val pickedBatchId: UUID? = null
)

data class Basket(val lines: List<LineInput>)

/**
 * The engine's answer for one line (doc 23 section 3.5, Outputs). unitPrice is the price the
 * customer pays per unit after caps and the line rule; discountAmount is what the rule took off
 * the whole line; lineTotal = unitPrice × qty. taxAmount is the tax inside lineTotal less the
 * line's share of a bill discount.
 */
data class LineResult(
    val input: LineInput,
    val sellable: Boolean,
    val reason: String? = null,
    val batch: BatchCandidate? = null,
    val basePrice: Money = Money.ZERO,
    val unitPrice: Money = Money.ZERO,
    val mrpApplied: Money? = null,
    val controlPriceApplied: Money? = null,
    val capReason: CapReason = CapReason.NONE,
    val ruleId: UUID? = null,
    val markdown: Boolean = false,
    val discountAmount: Money = Money.ZERO,
    val taxRatePercent: BigDecimal = BigDecimal.ZERO,
    val taxAmount: Money = Money.ZERO,
    val lineTotal: Money = Money.ZERO,
    val candidates: List<BatchCandidate> = emptyList(),
    /**
     * The batch an expiry markdown is judged on: the scanned or picked batch, else the FEFO-first
     * in-date candidate (CR-23A-1). When a markdown applies, it becomes [batch], so the sale
     * depletes the units that were marked down.
     */
    val markdownBatch: BatchCandidate? = null
) {
    val skuId: UUID get() = input.skuId

    /** The till shows the picker: the policy is PICKER and the candidate MRPs differ too much. */
    val needsPick: Boolean get() = !sellable && reason == NEEDS_PICK

    companion object {
        const val NO_LIST_LINE = "price.no_list_line"
        const val NEEDS_PICK = "price.needs_pick"

        fun notSellable(input: LineInput, reason: String): LineResult =
            LineResult(input = input, sellable = false, reason = reason)

        fun needsPick(input: LineInput, candidates: List<BatchCandidate>): LineResult =
            LineResult(input = input, sellable = false, reason = NEEDS_PICK, candidates = candidates)
    }
}

/** The bill-level benefit (doc 23 section 3.3, BILL_THRESHOLD): at most one per receipt. */
data class BillBenefit(val ruleId: UUID, val amount: Money)

data class BasketResult(
    val lines: List<LineResult>,
    val billRuleId: UUID?,
    val billDiscount: Money,
    val subtotal: Money,
    val total: Money,
    val rounding: Money,
    val engineVersion: String,
    val snapshotVersion: Long
)

/**
 * A line of a TRADE list (doc 23 section 3.2): tax-exclusive, with a unit price of four
 * decimals (doc 18: unit price is numeric(14,4)) and the quantity from which its tier applies.
 */
data class TradeLine(
    val lineId: UUID,
    val skuId: UUID,
    val uom: String,
    val tierFromQty: Quantity,
    val price: BigDecimal,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate? = null
) {
    fun inForce(date: LocalDate): Boolean =
        !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo))
}

/** The trade price that applies to an order line: the tier chosen and its unit price. */
data class TradeQuote(
    val lineId: UUID,
    val skuId: UUID,
    val uom: String,
    val tierFromQty: Quantity,
    val unitPrice: BigDecimal,
    val engineVersion: String
)
