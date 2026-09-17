package lk.coopfed.knoweb.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyTest {

    @Test
    fun cashRoundingParity() {

        assertEquals(
            Money.of("100.49"),
            Money.roundCash(
                Money.of("100.49")
            )
        )

        assertEquals(
            Money.of("101.00"),
            Money.roundCash(
                Money.of("100.50")
            )
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