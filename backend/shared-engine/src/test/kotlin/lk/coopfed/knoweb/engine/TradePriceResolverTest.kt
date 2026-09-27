package lk.coopfed.knoweb.engine

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Volume tiers of a TRADE list (doc 23 section 3.2; doc 10 A-03: the tier basis is the ordered quantity). */
class TradePriceResolverTest {

    private val sku = UUID(0L, 1L)
    private val from = LocalDate.of(2026, 10, 1)
    private val lines = listOf(
        TradeLine(UUID(0L, 10L), sku, "CASE", Quantity.of("0"), BigDecimal("1000.0000"), from),
        TradeLine(UUID(0L, 11L), sku, "CASE", Quantity.of("10"), BigDecimal("950.0000"), from),
        TradeLine(UUID(0L, 12L), sku, "CASE", Quantity.of("50"), BigDecimal("900.5000"), from, LocalDate.of(2026, 12, 31))
    )

    @Test
    fun theHighestTierNotExceedingTheQuantityApplies() {
        assertEquals(BigDecimal("1000.0000"), price("9.999"))
        assertEquals(BigDecimal("950.0000"), price("10"))
        assertEquals(BigDecimal("950.0000"), price("49"))
        assertEquals(BigDecimal("900.5000"), price("50"))
    }

    @Test
    fun aClosedTierNoLongerApplies() {
        val quote = TradePriceResolver.resolve(lines, sku, "CASE", Quantity.of("60"), LocalDate.of(2027, 1, 1))
        assertEquals(BigDecimal("950.0000"), quote?.unitPrice)
    }

    @Test
    fun noLineInForceOrForTheUnitGivesNothing() {
        assertNull(TradePriceResolver.resolve(lines, sku, "CASE", Quantity.of("1"), from.minusDays(1)))
        assertNull(TradePriceResolver.resolve(lines, sku, "EA", Quantity.of("1"), from))
    }

    @Test
    fun tiersMustStartAtZeroAndRise() {
        assertNull(TradePriceResolver.tierFault(listOf(Quantity.of("0"), Quantity.of("10"))))
        assertEquals(
            "m3.price_list.line.tier_base_missing",
            TradePriceResolver.tierFault(listOf(Quantity.of("5"), Quantity.of("10")))
        )
        assertEquals(
            "m3.price_list.line.tiers_not_ascending",
            TradePriceResolver.tierFault(listOf(Quantity.of("0"), Quantity.of("10"), Quantity.of("10")))
        )
    }

    private fun price(qty: String): BigDecimal? =
        TradePriceResolver.resolve(lines, sku, "CASE", Quantity.of(qty), from)?.unitPrice
}
