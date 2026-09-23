package lk.coopfed.knoweb.engine

import java.math.BigDecimal
import java.math.RoundingMode

data class Money private constructor(
    val amount: BigDecimal
) : Comparable<Money> {

    companion object {

        @JvmStatic
        fun of(value: String): Money =
            Money(
                BigDecimal(value)
                    .setScale(2, RoundingMode.UNNECESSARY)
            )

        @JvmStatic
        fun zero(): Money =
            Money(BigDecimal.ZERO.setScale(2))

        /**
        * Returns the cash amount rounded to the nearest whole rupee.
        *
        * Receipt resolution uses Rounding.toRupee() when the
        * rounding adjustment must also be recorded separately.
        */
        @JvmStatic
        fun roundCash(value: Money): Money =
            Rounding.roundCash(value)
    }

    operator fun plus(other: Money): Money =
        Money(
            amount.add(other.amount)
                .setScale(2, RoundingMode.UNNECESSARY)
        )

    operator fun minus(other: Money): Money =
        Money(
            amount.subtract(other.amount)
                .setScale(2, RoundingMode.UNNECESSARY)
        )

    override fun compareTo(other: Money): Int =
        amount.compareTo(other.amount)

    override fun toString(): String =
        amount.toPlainString()
}