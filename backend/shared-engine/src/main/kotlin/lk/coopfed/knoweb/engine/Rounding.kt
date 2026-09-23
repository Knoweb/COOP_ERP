package lk.coopfed.knoweb.engine

import java.math.RoundingMode

object Rounding {

    /**
     * Returns the adjustment required to round a cash receipt total
     * to the nearest whole rupee.
     *
     * Examples:
     * 100.49 -> -0.49
     * 100.50 ->  0.50
     * 100.51 ->  0.49
     */
    @JvmStatic
    fun toRupee(value: Money): Money {
        val rounded =
            value.amount
                .setScale(0, RoundingMode.HALF_UP)
                .setScale(2)

        return Money.of(
            rounded
                .subtract(value.amount)
                .toPlainString()
        )
    }

    /**
     * Compatibility helper retained for existing Sprint 0 consumers.
     */
    @JvmStatic
    fun roundCash(value: Money): Money =
        value + toRupee(value)
}
