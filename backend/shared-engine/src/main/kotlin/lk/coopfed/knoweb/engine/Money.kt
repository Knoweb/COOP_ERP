package lk.coopfed.knoweb.engine

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * An amount of rupees with two decimals (doc 18: money is numeric(14,2)). Arithmetic that can
 * produce more decimals (a percentage, a unit price times a quantity) rounds half up to the
 * cent, always in the same place, so central and the till give the same bytes (23A section 6,
 * "Determinism").
 */
data class Money private constructor(
    val amount: BigDecimal
) : Comparable<Money> {

    companion object {

        @JvmField
        val ZERO: Money = Money(BigDecimal.ZERO.setScale(2))

        /** Larger than any real price: the neutral element of "lowest of". */
        @JvmField
        val MAX: Money = Money(BigDecimal("999999999999.99"))

        /** Exact: a value with more than two decimals is refused, never silently rounded. */
        @JvmStatic
        fun of(value: String): Money =
            Money(
                BigDecimal(value)
                    .setScale(2, RoundingMode.UNNECESSARY)
            )

        /** Rounds half up to the cent: for values computed from prices and quantities. */
        @JvmStatic
        fun rounded(value: BigDecimal): Money =
            Money(value.setScale(2, RoundingMode.HALF_UP))

        @JvmStatic
        fun zero(): Money = ZERO

        /**
         * Kept from Sprint 0 (17A section 9): the rounded receipt total for a cash tender.
         * [Rounding.toRupee] gives the adjustment the receipt records.
         */
        @JvmStatic
        fun roundCash(value: Money): Money =
            Rounding.roundCash(value)
    }

    operator fun plus(other: Money): Money =
        Money(amount.add(other.amount).setScale(2, RoundingMode.UNNECESSARY))

    operator fun minus(other: Money): Money =
        Money(amount.subtract(other.amount).setScale(2, RoundingMode.UNNECESSARY))

    /** A unit amount times a quantity, rounded half up to the cent. */
    operator fun times(quantity: Quantity): Money =
        rounded(amount.multiply(quantity.value))

    /** The given percentage of this amount, rounded half up to the cent. */
    fun percent(percent: BigDecimal): Money =
        rounded(amount.multiply(percent).divide(BigDecimal(100)))

    fun min(other: Money): Money = if (other < this) other else this

    fun max(other: Money): Money = if (other > this) other else this

    fun isNegative(): Boolean = amount.signum() < 0

    override fun compareTo(other: Money): Int =
        amount.compareTo(other.amount)

    override fun toString(): String =
        amount.toPlainString()
}
