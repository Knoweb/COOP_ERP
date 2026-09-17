package lk.coopfed.knoweb.engine

import java.math.BigDecimal
import java.math.RoundingMode

object Rounding {

    /**
     * Sprint 0 deterministic placeholder.
     *
     * The real legal/pricing rounding engine is implemented by M3/23A.
     */
    @JvmStatic
    fun roundCash(value: Money): Money {

        val amount = value.amount

        val whole =
            amount.setScale(0, RoundingMode.DOWN)

        val fraction =
            amount.subtract(whole)

        return if (
            fraction >= BigDecimal("0.50")
        ) {
            Money.of(
                whole
                    .add(BigDecimal.ONE)
                    .setScale(2)
                    .toPlainString()
            )
        } else {
            value
        }
    }
}