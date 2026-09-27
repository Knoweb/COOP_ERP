package lk.coopfed.knoweb.engine

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The steps of doc 23 section 3.5, one case each, and doc 13 scenario 4 to the cent (23A section 5). */
class PricingEngineTest {

    private val today = LocalDate.of(2026, 10, 1)
    private val sku = id(1)
    private val batch980 = BatchCandidate(id(11), "B980", Money.of("980.00"), LocalDate.of(2027, 3, 1), Quantity.of("5"))
    private val batch1040 = BatchCandidate(id(12), "B1040", Money.of("1040.00"), LocalDate.of(2027, 6, 1), Quantity.of("5"))
    private val facts = SkuFacts(sku, hasPrintedMrp = true, taxRatePercent = BigDecimal("18.00"), tags = setOf("dairy"))
    private val listLine = RetailLine(sku, "EA", Money.of("1040.00"), LocalDate.of(2026, 1, 1))
    private val fivePercent = Rule(
        ruleId = id(21), kind = RuleKind.TIME_LIMITED_PRICE, priority = 100,
        validFrom = LocalDate.of(2026, 9, 1), validTo = LocalDate.of(2026, 10, 31),
        skuId = sku, benefitKind = BenefitKind.PERCENT_OFF, benefitValue = BigDecimal("5")
    )

    private fun index(
        policy: Policy = Policy.DEFAULT,
        rules: List<Rule> = emptyList(),
        ceilings: List<Ceiling> = emptyList(),
        batches: List<BatchCandidate> = listOf(batch980, batch1040),
        skuFacts: SkuFacts = facts
    ) = PricingSnapshotIndex(
        snapshotVersion = 4182,
        stackingPolicy = StackingPolicy.PRIORITY_THEN_BEST,
        skus = listOf(skuFacts),
        retailLines = listOf(listLine),
        batches = mapOf(sku to batches),
        policies = mapOf(sku to policy),
        skuCeilings = mapOf(sku to ceilings),
        rules = rules
    )

    @Test
    fun scenario4TwoBatchesAutoLowestAndAFivePercentRule() {
        val result = BasketResolver.resolve(
            Basket(listOf(LineInput(sku, "EA", Quantity.of("1")))), index(rules = listOf(fivePercent)), today, TenderKind.CASH
        )
        val line = result.lines.single()
        assertEquals(batch980.batchId, line.batch?.batchId)
        assertEquals(Money.of("931.00"), line.unitPrice)
        assertEquals(Money.of("980.00"), line.mrpApplied)
        assertEquals(CapReason.MRP_LOWEST, line.capReason)
        assertEquals(fivePercent.ruleId, line.ruleId)
        assertEquals(Money.of("49.00"), line.discountAmount)
        assertEquals(Money.of("142.02"), line.taxAmount)
        assertEquals(Money.of("931.00"), line.lineTotal)
        assertEquals(Money.of("931.00"), result.subtotal)
        assertEquals(Money.of("931.00"), result.total)
        assertEquals(Money.ZERO, result.rounding)
        assertEquals(4182, result.snapshotVersion)
    }

    @Test
    fun noListLineIsNotSellable() {
        val line = PriceResolver.resolveLine(LineInput(sku, "KG", Quantity.of("1")), index(), today)
        assertFalse(line.sellable)
        assertEquals(LineResult.NO_LIST_LINE, line.reason)
    }

    @Test
    fun aSkuWithoutPrintedMrpHasNoBatchTerm() {
        val line = PriceResolver.resolveLine(
            LineInput(sku, "EA", Quantity.of("1")), index(skuFacts = facts.copy(hasPrintedMrp = false)), today
        )
        assertEquals(Money.of("1040.00"), line.unitPrice)
        assertEquals(CapReason.NONE, line.capReason)
        assertNull(line.mrpApplied)
    }

    @Test
    fun barcodeResolvedUsesTheScannedBatch() {
        val line = PriceResolver.resolveLine(
            LineInput(sku, "EA", Quantity.of("1"), scannedBatchId = batch1040.batchId),
            index(policy = Policy(PolicyKind.BARCODE_RESOLVED)), today
        )
        assertEquals(Money.of("1040.00"), line.unitPrice)
        assertEquals(CapReason.NONE, line.capReason, "the batch MRP equals the list price, so it did not bind")
        assertEquals(batch1040.batchId, line.batch?.batchId)
    }

    @Test
    fun pickerShowsThePickerWhenTheGapIsLargeAndUsesThePickedBatch() {
        val picker = index(policy = Policy(PolicyKind.PICKER, Money.of("20.00"), BigDecimal("5")))
        val unpicked = PriceResolver.resolveLine(LineInput(sku, "EA", Quantity.of("1")), picker, today)
        assertTrue(unpicked.needsPick)
        assertEquals(2, unpicked.candidates.size)

        val picked = PriceResolver.resolveLine(
            LineInput(sku, "EA", Quantity.of("1"), pickedBatchId = batch980.batchId), picker, today
        )
        assertEquals(Money.of("980.00"), picked.unitPrice)
        assertEquals(CapReason.MRP_PICKED, picked.capReason)
    }

    @Test
    fun pickerWithASmallGapFallsBackToTheLowest() {
        val close = batch1040.copy(printedMrp = Money.of("990.00"))
        val line = PriceResolver.resolveLine(
            LineInput(sku, "EA", Quantity.of("1")),
            index(policy = Policy(PolicyKind.PICKER, Money.of("20.00"), BigDecimal("5")), batches = listOf(batch980, close)),
            today
        )
        assertTrue(line.sellable)
        assertEquals(CapReason.MRP_LOWEST, line.capReason)
    }

    @Test
    fun theControlPriceBindsBelowEverything() {
        val gazette = Ceiling(Money.of("900.00"), "EA", "2492/29", LocalDate.of(2026, 9, 30))
        val line = PriceResolver.resolveLine(LineInput(sku, "EA", Quantity.of("2")), index(ceilings = listOf(gazette)), today)
        assertEquals(Money.of("900.00"), line.unitPrice)
        assertEquals(CapReason.CONTROL_PRICE, line.capReason)
        assertEquals(Money.of("1800.00"), line.lineTotal)
    }

    @Test
    fun aCeilingNotYetInForceDoesNotBind() {
        val later = Ceiling(Money.of("900.00"), "EA", "2492/30", LocalDate.of(2026, 10, 2))
        val line = PriceResolver.resolveLine(LineInput(sku, "EA", Quantity.of("1")), index(ceilings = listOf(later)), today)
        assertEquals(CapReason.MRP_LOWEST, line.capReason)
    }

    @Test
    fun expiryMarkdownAppliesOnlyToABatchInsideTheWindow() {
        val markdown = Rule(
            ruleId = id(22), kind = RuleKind.EXPIRY_MARKDOWN, priority = 50,
            validFrom = LocalDate.of(2026, 1, 1), validTo = null, tag = "dairy", daysToExpiry = 5,
            benefitKind = BenefitKind.PERCENT_OFF, benefitValue = BigDecimal("30")
        )
        val soon = batch980.copy(expiry = today.plusDays(3))
        val line = BasketResolver.resolve(
            Basket(listOf(LineInput(sku, "EA", Quantity.of("1")))),
            index(rules = listOf(markdown), batches = listOf(soon)), today, TenderKind.CARD
        ).lines.single()
        assertEquals(Money.of("686.00"), line.unitPrice)
        assertTrue(line.markdown)

        val fresh = BasketResolver.resolve(
            Basket(listOf(LineInput(sku, "EA", Quantity.of("1")))),
            index(rules = listOf(markdown)), today, TenderKind.CARD
        ).lines.single()
        assertNull(fresh.ruleId)
    }

    @Test
    fun theLowerPriorityNumberWinsAndBenefitsNeverCompound() {
        val tenOff = fivePercent.copy(ruleId = id(23), priority = 200, benefitValue = BigDecimal("10"))
        val line = BasketResolver.resolve(
            Basket(listOf(LineInput(sku, "EA", Quantity.of("1")))),
            index(rules = listOf(fivePercent, tenOff)), today, TenderKind.CARD
        ).lines.single()
        assertEquals(fivePercent.ruleId, line.ruleId)
        assertEquals(Money.of("931.00"), line.unitPrice)
    }

    @Test
    fun aBillThresholdRuleIsApportionedForTaxAndCashRoundsTheTotal() {
        val bill = Rule(
            ruleId = id(24), kind = RuleKind.BILL_THRESHOLD, priority = 100,
            validFrom = LocalDate.of(2026, 1, 1), validTo = null, billThreshold = Money.of("1000.00"),
            benefitKind = BenefitKind.AMOUNT_OFF, benefitValue = BigDecimal("10.51")
        )
        val result = BasketResolver.resolve(
            Basket(listOf(LineInput(sku, "EA", Quantity.of("2")))), index(rules = listOf(bill)), today, TenderKind.CASH
        )
        assertEquals(Money.of("1960.00"), result.subtotal)
        assertEquals(Money.of("10.51"), result.billDiscount)
        assertEquals(Money.of("-0.49"), result.rounding)
        assertEquals(Money.of("1949.00"), result.total)
        assertEquals(Tax.inclusivePart(Money.of("1949.49"), BigDecimal("18.00")), result.lines.single().taxAmount)
    }

    @Test
    fun cashRoundingIsHalfUpToTheRupee() {
        assertEquals(Money.of("-0.49"), Rounding.toRupee(Money.of("100.49")))
        assertEquals(Money.of("0.50"), Rounding.toRupee(Money.of("100.50")))
        assertEquals(Money.of("0.49"), Rounding.toRupee(Money.of("100.51")))
    }

    private fun id(n: Int): UUID = UUID(0L, n.toLong())
}
