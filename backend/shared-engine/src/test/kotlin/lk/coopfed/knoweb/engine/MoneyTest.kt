package lk.coopfed.knoweb.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyTest {

    @Test
    fun cashRoundingRoundsDownBelowHalfRupee() {
        assertEquals(
            Money.of("100.00"),
            Money.roundCash(Money.of("100.49"))
        )
    }

    @Test
    fun cashRoundingRoundsUpAtHalfRupee() {
        assertEquals(
            Money.of("101.00"),
            Money.roundCash(Money.of("100.50"))
        )
    }

    @Test
    fun cashRoundingRoundsUpAboveHalfRupee() {
        assertEquals(
            Money.of("101.00"),
            Money.roundCash(Money.of("100.51"))
        )
    }

    @Test
    fun cashRoundingAdjustmentIsRecordedSeparately() {
        assertEquals(
            Money.of("-0.49"),
            Rounding.toRupee(Money.of("100.49"))
        )
        assertEquals(
            Money.of("0.50"),
            Rounding.toRupee(Money.of("100.50"))
        )
        assertEquals(
            Money.of("0.49"),
            Rounding.toRupee(Money.of("100.51"))
        )
    }

    @Test
    fun wholeRupeeNeedsNoRoundingAdjustment() {
        assertEquals(
            Money.of("0.00"),
            Rounding.toRupee(Money.of("931.00"))
        )
    }

    @Test
    fun moneyUsesExactDecimalArithmetic() {
        val result =
            Money.of("10.10") +
                Money.of("20.20")

        assertEquals(
            Money.of("30.30"),
            result
        )
    }
}
