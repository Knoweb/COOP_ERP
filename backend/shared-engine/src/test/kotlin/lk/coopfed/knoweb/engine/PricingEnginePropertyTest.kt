package lk.coopfed.knoweb.engine

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Random
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import lk.coopfed.knoweb.engine.model.Basket
import lk.coopfed.knoweb.engine.model.BatchCandidate
import lk.coopfed.knoweb.engine.model.BenefitKind
import lk.coopfed.knoweb.engine.model.CapReason
import lk.coopfed.knoweb.engine.model.Ceiling
import lk.coopfed.knoweb.engine.model.LineInput
import lk.coopfed.knoweb.engine.model.RetailLine
import lk.coopfed.knoweb.engine.model.Rule
import lk.coopfed.knoweb.engine.model.RuleKind
import lk.coopfed.knoweb.engine.model.SkuFacts
import lk.coopfed.knoweb.engine.model.StackingPolicy
import lk.coopfed.knoweb.engine.model.TenderKind
import lk.coopfed.knoweb.engine.snapshot.PricingSnapshotIndex

/**
 * The engine's properties over random snapshots and baskets (23A section 9, "Engine
 * property-based"): the legal one first, no line above a control price in force, then the total
 * never above the list, the cap reason consistent with the numbers, and the same inputs giving
 * the same result. A fixed seed, so a failure reproduces.
 */
class PricingEnginePropertyTest {

    private val date = LocalDate.of(2026, 10, 1)

    @Test
    fun tenThousandRandomBaskets() {
        val random = Random(20260927L)
        repeat(10_000) { case ->
            val (ix, basket) = randomCase(random)
            val tender = if (random.nextBoolean()) TenderKind.CASH else TenderKind.CARD
            val result = BasketResolver.resolve(basket, ix, date, tender)

            var listTotal = Money.ZERO
            for (line in result.lines.filter { it.sellable }) {
                val control = ix.ceilingFor(line.skuId, line.input.uom, date)
                if (control != null) {
                    assertTrue(line.unitPrice <= control.price, "case $case: a line above the control price")
                }
                val list = ix.retailLine(line.skuId, line.input.uom, date)!!
                assertTrue(line.unitPrice <= list.price, "case $case: a line above its list price")
                assertTrue(!line.unitPrice.isNegative(), "case $case: a negative price")
                when (line.capReason) {
                    CapReason.CONTROL_PRICE -> assertEquals(control?.price, line.basePrice, "case $case")
                    CapReason.MRP_LOWEST -> assertEquals(line.mrpApplied, line.basePrice, "case $case: MRP_LOWEST that did not bind")
                    else -> Unit
                }
                listTotal += list.price * line.input.qty
            }
            assertTrue(result.total - result.rounding <= listTotal, "case $case: a receipt above the list")
            assertEquals(result, BasketResolver.resolve(basket, ix, date, tender), "case $case: re-resolution differs")
        }
    }

    private fun randomCase(random: Random): Pair<PricingSnapshotIndex, Basket> {
        val skuCount = 1 + random.nextInt(6)
        val skus = (1..skuCount).map { UUID(1L, it.toLong()) }
        val facts = skus.map { SkuFacts(it, random.nextBoolean(), BigDecimal(listOf("0", "8", "18")[random.nextInt(3)]), setOf("t" + random.nextInt(3))) }
        val lines = skus.map { RetailLine(it, "EA", money(random, 50, 2000), date.minusDays(random.nextInt(30).toLong())) }
        val batches = skus.associateWith { sku ->
            (0 until random.nextInt(4)).map { n ->
                BatchCandidate(UUID(2L, sku.leastSignificantBits * 10 + n), "B$n", money(random, 40, 2100), date.plusDays(random.nextInt(60).toLong()), Quantity.of(random.nextInt(3).toString()))
            }
        }
        val ceilings = skus.associateWith { if (random.nextInt(4) == 0) listOf(Ceiling(money(random, 30, 2000), "EA", "G", date.minusDays(1))) else emptyList() }
        val tagCeilings = if (random.nextInt(5) == 0) mapOf("t0" to listOf(Ceiling(money(random, 30, 2000), "EA", "T", date))) else emptyMap()
        val rules = (0 until random.nextInt(4)).map { n ->
            val kind = listOf(RuleKind.TIME_LIMITED_PRICE, RuleKind.QUANTITY_BREAK, RuleKind.EXPIRY_MARKDOWN, RuleKind.BILL_THRESHOLD)[random.nextInt(4)]
            val benefit = if (kind == RuleKind.BILL_THRESHOLD) listOf(BenefitKind.PERCENT_OFF, BenefitKind.AMOUNT_OFF)[random.nextInt(2)]
            else BenefitKind.values()[random.nextInt(3)]
            Rule(
                ruleId = UUID(3L, n.toLong()), kind = kind, priority = random.nextInt(3) * 50,
                validFrom = date.minusDays(1), validTo = null,
                skuId = skus[random.nextInt(skus.size)], tag = if (random.nextBoolean()) "t1" else null,
                minQty = Quantity.of("2"), daysToExpiry = 10, billThreshold = money(random, 100, 3000),
                benefitKind = benefit,
                benefitValue = if (benefit == BenefitKind.PERCENT_OFF) BigDecimal(random.nextInt(60)) else money(random, 1, 2500).amount
            )
        }
        val ix = PricingSnapshotIndex(
            snapshotVersion = 1, stackingPolicy = StackingPolicy.values()[random.nextInt(2)],
            skus = facts, retailLines = lines, batches = batches, skuCeilings = ceilings, tagCeilings = tagCeilings, rules = rules
        )
        val basket = Basket((0 until 1 + random.nextInt(5)).map {
            LineInput(skus[random.nextInt(skus.size)], "EA", Quantity.of((1 + random.nextInt(4)).toString()))
        })
        return ix to basket
    }

    private fun money(random: Random, low: Int, high: Int): Money =
        Money.of(BigDecimal(low + random.nextInt(high - low)).add(BigDecimal(random.nextInt(100)).movePointLeft(2)).toPlainString())
}
