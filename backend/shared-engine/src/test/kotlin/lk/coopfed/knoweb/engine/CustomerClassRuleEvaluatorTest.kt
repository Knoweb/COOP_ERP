package lk.coopfed.knoweb.engine

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CustomerClassRuleEvaluatorTest {

    private val today = LocalDate.of(2026, 11, 4)
    private val sku = UUID.randomUUID()
    private val facts = SkuFacts(sku, hasPrintedMrp = false, taxRatePercent = BigDecimal.ZERO)
    private val listLine = RetailLine(sku, "EA", Money.of("100.00"), LocalDate.of(2026, 1, 1))

    private val generalRule = Rule(
        ruleId = UUID.randomUUID(),
        kind = RuleKind.TIME_LIMITED_PRICE,
        priority = 100,
        validFrom = LocalDate.of(2026, 1, 1),
        validTo = null,
        skuId = sku,
        benefitKind = BenefitKind.AMOUNT_OFF,
        benefitValue = BigDecimal("5.00"),
        customerClass = null
    )

    private val memberRule = Rule(
        ruleId = UUID.randomUUID(),
        kind = RuleKind.TIME_LIMITED_PRICE,
        priority = 50,
        validFrom = LocalDate.of(2026, 1, 1),
        validTo = null,
        skuId = sku,
        benefitKind = BenefitKind.AMOUNT_OFF,
        benefitValue = BigDecimal("15.00"),
        customerClass = "MEMBER"
    )

    private val employeeRule = Rule(
        ruleId = UUID.randomUUID(),
        kind = RuleKind.TIME_LIMITED_PRICE,
        priority = 40,
        validFrom = LocalDate.of(2026, 1, 1),
        validTo = null,
        skuId = sku,
        benefitKind = BenefitKind.AMOUNT_OFF,
        benefitValue = BigDecimal("20.00"),
        customerClass = "EMPLOYEE"
    )

    private fun index(rules: List<Rule>) = PricingSnapshotIndex(
        snapshotVersion = 1L,
        stackingPolicy = StackingPolicy.PRIORITY_THEN_BEST,
        skus = listOf(facts),
        retailLines = listOf(listLine),
        batches = emptyMap(),
        rules = rules
    )

    @Test
    fun generalRuleWorksWithoutBeneficiary() {
        val basket = Basket(listOf(LineInput(sku, "EA", Quantity.of("1"))), null)
        val result = BasketResolver.resolve(basket, index(listOf(generalRule, memberRule, employeeRule)), today, TenderKind.CASH)
        val line = result.lines.single()
        assertEquals(generalRule.ruleId, line.ruleId)
        assertEquals(Money.of("95.00"), line.unitPrice)
        assertEquals(Money.of("5.00"), line.discountAmount)
    }

    @Test
    fun memberRuleMatchesVerifiedMember() {
        val basket = Basket(listOf(LineInput(sku, "EA", Quantity.of("1"))), "MEMBER")
        val result = BasketResolver.resolve(basket, index(listOf(generalRule, memberRule, employeeRule)), today, TenderKind.CASH)
        val line = result.lines.single()
        assertEquals(memberRule.ruleId, line.ruleId)
        assertEquals(Money.of("85.00"), line.unitPrice)
        assertEquals(Money.of("15.00"), line.discountAmount)
    }

    @Test
    fun memberRuleDoesNotMatchEmployee() {
        // Only memberRule is present in index
        val basket = Basket(listOf(LineInput(sku, "EA", Quantity.of("1"))), "EMPLOYEE")
        val result = BasketResolver.resolve(basket, index(listOf(memberRule)), today, TenderKind.CASH)
        val line = result.lines.single()
        assertNull(line.ruleId)
        assertEquals(Money.of("100.00"), line.unitPrice)
        assertEquals(Money.ZERO, line.discountAmount)
    }

    @Test
    fun employeeRuleMatchesVerifiedEmployee() {
        val basket = Basket(listOf(LineInput(sku, "EA", Quantity.of("1"))), "EMPLOYEE")
        val result = BasketResolver.resolve(basket, index(listOf(generalRule, memberRule, employeeRule)), today, TenderKind.CASH)
        val line = result.lines.single()
        assertEquals(employeeRule.ruleId, line.ruleId)
        assertEquals(Money.of("80.00"), line.unitPrice)
        assertEquals(Money.of("20.00"), line.discountAmount)
    }

    @Test
    fun employeeRuleDoesNotMatchMember() {
        // Only employeeRule is present in index
        val basket = Basket(listOf(LineInput(sku, "EA", Quantity.of("1"))), "MEMBER")
        val result = BasketResolver.resolve(basket, index(listOf(employeeRule)), today, TenderKind.CASH)
        val line = result.lines.single()
        assertNull(line.ruleId)
        assertEquals(Money.of("100.00"), line.unitPrice)
        assertEquals(Money.ZERO, line.discountAmount)
    }

    @Test
    fun invalidOrUnverifiedCredentialDoesNotActivateCustomerClassRule() {
        val basket = Basket(listOf(LineInput(sku, "EA", Quantity.of("1"))), null)
        val result = BasketResolver.resolve(basket, index(listOf(memberRule, employeeRule)), today, TenderKind.CASH)
        val line = result.lines.single()
        assertNull(line.ruleId)
        assertEquals(Money.of("100.00"), line.unitPrice)
    }

    @Test
    fun stackingAndPriorityBehaviorRemainsUnchanged() {
        // If employee has a lower priority rule than member, but basket is for MEMBER, member rule still wins
        val basket = Basket(listOf(LineInput(sku, "EA", Quantity.of("1"))), "MEMBER")
        val result = BasketResolver.resolve(basket, index(listOf(generalRule, memberRule, employeeRule)), today, TenderKind.CASH)
        val line = result.lines.single()
        assertEquals(memberRule.ruleId, line.ruleId)
        assertEquals(Money.of("85.00"), line.unitPrice)
    }

    @Test
    fun branchRuleOnlyAppliesAtTargetShopLocation() {
        val targetShop = UUID.randomUUID()
        val otherShop = UUID.randomUUID()
        val branchRule = Rule(
            ruleId = UUID.randomUUID(),
            kind = RuleKind.TIME_LIMITED_PRICE,
            priority = 10,
            validFrom = LocalDate.of(2026, 1, 1),
            validTo = null,
            skuId = sku,
            benefitKind = BenefitKind.AMOUNT_OFF,
            benefitValue = BigDecimal("25.00"),
            customerClass = null,
            locationId = targetShop
        )

        // Snapshot index for target shop includes entity-wide and target branch rule
        val targetIndex = index(listOf(generalRule, branchRule).filter { it.locationId == null || it.locationId == targetShop })
        val targetBasket = Basket(listOf(LineInput(sku, "EA", Quantity.of("1"))), null)
        val targetResult = BasketResolver.resolve(targetBasket, targetIndex, today, TenderKind.CASH)
        assertEquals(branchRule.ruleId, targetResult.lines.single().ruleId)
        assertEquals(Money.of("75.00"), targetResult.lines.single().unitPrice)

        // Snapshot index for other shop excludes the target branch rule
        val otherIndex = index(listOf(generalRule, branchRule).filter { it.locationId == null || it.locationId == otherShop })
        val otherBasket = Basket(listOf(LineInput(sku, "EA", Quantity.of("1"))), null)
        val otherResult = BasketResolver.resolve(otherBasket, otherIndex, today, TenderKind.CASH)
        assertEquals(generalRule.ruleId, otherResult.lines.single().ruleId)
        assertEquals(Money.of("95.00"), otherResult.lines.single().unitPrice)
    }
}

