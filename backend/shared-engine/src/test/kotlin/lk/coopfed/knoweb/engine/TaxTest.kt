package lk.coopfed.knoweb.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TaxTest {

    @Test
    fun taxRateIsRepresentedAsPercent() {
        val tax =
            Tax.of(
                code = "VAT",
                ratePercent = "18.00"
            )

        assertEquals(
            "18.00",
            tax.ratePercent.toPlainString()
        )
    }

    @Test
    fun taxRateUsesTwoDecimalPlaces() {
        val tax =
            Tax.of(
                code = "VAT",
                ratePercent = "18"
            )

        assertEquals(
            "18.00",
            tax.ratePercent.toPlainString()
        )
    }

    @Test
    fun taxRateRejectsMoreThanTwoNonZeroDecimalPlaces() {
        assertFailsWith<ArithmeticException> {
            Tax.of(
                code = "VAT",
                ratePercent = "18.001"
            )
        }
    }
}