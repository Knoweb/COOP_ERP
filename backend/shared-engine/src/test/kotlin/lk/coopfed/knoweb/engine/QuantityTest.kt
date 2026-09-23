package lk.coopfed.knoweb.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QuantityTest {

    @Test
    fun quantityUsesThreeDecimalPlaces() {
        assertEquals(
            "1.250",
            Quantity.of("1.250").toString()
        )
    }

    @Test
    fun quantitiesCompareExactly() {
        assertTrue(
            Quantity.of("2.000") >
                Quantity.of("1.999")
        )
    }

    @Test
    fun zeroQuantityIsNotPositive() {
        assertTrue(
            Quantity.zero() <=
                Quantity.zero()
        )
    }

    @Test
    fun quantityRejectsMoreThanThreeNonZeroDecimalPlaces() {
        assertFailsWith<ArithmeticException> {
            Quantity.of("1.2345")
        }
    }
}