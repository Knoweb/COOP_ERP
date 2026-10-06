package lk.coopfed.knoweb.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Step 5 of doc 23 section 3.5: at most one line rule per line, chosen by the stacking policy
 * (DR-1, PRIORITY_THEN_BEST: the lowest priority number, ties to the best benefit for the
 * customer), then at most one bill rule. Benefits never compound.
 *
 * FREE_ITEM is not evaluated yet: it adds a zero-priced line only when the free item is in stock
 * at the till, which needs the till's stock view; deferred for the demo (M3-02, see PROGRESS).
 */
object RuleEvaluator {

    @JvmStatic
    fun applyLineRules(r: LineResult, ix: PricingSnapshotIndex, date: LocalDate, policy: StackingPolicy): LineResult {
        if (!r.sellable) {
            return r
        }
        // A rule that gives this line nothing is dropped before precedence, as the bill path
        // does, so it cannot take a real discount away from the customer (M3-01).
        val applicable = ix.lineRulesFor(r.skuId, date)
            .filter { matches(it, r, date) }
            .filter { lineDiscount(it, r) > Money.ZERO }
        val chosen = choose(applicable, policy) { lineDiscount(it, r) } ?: return r
        val discount = lineDiscount(chosen, r)
        val perUnit = perUnitDiscount(chosen, r.basePrice)
        val unit = r.basePrice - perUnit
        val markdown = chosen.kind == RuleKind.EXPIRY_MARKDOWN
        return r.copy(
            unitPrice = unit,
            ruleId = chosen.ruleId,
            markdown = markdown,
            // The marked-down units are the ones sold, so M5 depletes that batch; mrpApplied
            // stays the policy's MRP, which already bounded the base price.
            batch = if (markdown) r.markdownBatch else r.batch,
            discountAmount = discount,
            lineTotal = unit * r.input.qty
        )
    }

    /** The one BILL_THRESHOLD rule that applies to the receipt, if any. */
    @JvmStatic
    fun applyBillRule(
        lines: List<LineResult>,
        ix: PricingSnapshotIndex,
        date: LocalDate,
        policy: StackingPolicy
    ): BillBenefit? {
        val rules = ix.billRules(date)
        val benefit = { rule: Rule -> billDiscount(rule, lines, ix) }
        val chosen = choose(rules.filter { benefit(it) > Money.ZERO }, policy, benefit) ?: return null
        return BillBenefit(chosen.ruleId, benefit(chosen))
    }

    private fun <T : Comparable<T>> choose(rules: List<Rule>, policy: StackingPolicy, benefit: (Rule) -> T): Rule? =
        when (policy) {
            StackingPolicy.PRIORITY_THEN_BEST -> rules.sortedWith(
                compareBy<Rule> { it.priority }
                    .thenByDescending { benefit(it) }
                    .thenBy { it.ruleId }
            ).firstOrNull()
            StackingPolicy.BEST_ONLY -> rules.sortedWith(
                compareByDescending<Rule> { benefit(it) }.thenBy { it.ruleId }
            ).firstOrNull()
        }

    private fun matches(rule: Rule, r: LineResult, date: LocalDate): Boolean =
        when (rule.kind) {
            RuleKind.TIME_LIMITED_PRICE -> rule.uom == null || rule.uom == r.input.uom
            RuleKind.QUANTITY_BREAK -> rule.minQty != null && r.input.qty >= rule.minQty
            RuleKind.EXPIRY_MARKDOWN -> {
                // 0 <= days <= D: an expired batch (days < 0) never takes a markdown (CR-23A-1).
                val expiry = r.markdownBatch?.expiry
                val days = expiry?.let { ChronoUnit.DAYS.between(date, it) }
                rule.daysToExpiry != null &&
                    days != null &&
                    days >= 0 &&
                    days <= rule.daysToExpiry
            }
            RuleKind.FREE_ITEM, RuleKind.BILL_THRESHOLD -> false
        }

    private fun perUnitDiscount(rule: Rule, base: Money): Money {
        val off = when (rule.benefitKind) {
            BenefitKind.FIXED_PRICE -> base - base.min(Money.rounded(rule.benefitValue))
            BenefitKind.PERCENT_OFF -> base.percent(rule.benefitValue)
            BenefitKind.AMOUNT_OFF -> Money.rounded(rule.benefitValue)
            BenefitKind.FREE_QTY -> Money.ZERO
        }
        return off.max(Money.ZERO).min(base)
    }

    private fun lineDiscount(rule: Rule, r: LineResult): Money =
        perUnitDiscount(rule, r.basePrice) * r.input.qty

    private fun billDiscount(rule: Rule, lines: List<LineResult>, ix: PricingSnapshotIndex): Money {
        val threshold = rule.billThreshold ?: return Money.ZERO
        val counted = lines
            .filter { it.sellable }
            .filter { rule.tag == null || rule.tag in ix.sku(it.skuId)?.tags.orEmpty() }
            .fold(Money.ZERO) { sum, line -> sum + line.lineTotal }
        if (counted < threshold) {
            return Money.ZERO
        }
        val off = when (rule.benefitKind) {
            BenefitKind.PERCENT_OFF -> counted.percent(rule.benefitValue)
            BenefitKind.AMOUNT_OFF -> Money.rounded(rule.benefitValue)
            BenefitKind.FIXED_PRICE, BenefitKind.FREE_QTY -> Money.ZERO
        }
        return off.max(Money.ZERO).min(counted)
    }
}
