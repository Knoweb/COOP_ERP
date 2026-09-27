package lk.coopfed.knoweb.engine

import java.math.BigDecimal
import java.math.RoundingMode

/** A quantity with three decimals (doc 18: quantity is numeric(14,3)); weighed goods need them. */
data class Quantity private constructor(
    val value: BigDecimal
) : Comparable<Quantity> {

    companion object {

        @JvmField
        val ZERO: Quantity = Quantity(BigDecimal.ZERO.setScale(3))

        @JvmStatic
        fun of(value: String): Quantity =
            of(BigDecimal(value))

        /** Exact: a value with more than three decimals is refused, never silently rounded. */
        @JvmStatic
        fun of(value: BigDecimal): Quantity =
            Quantity(value.setScale(3, RoundingMode.UNNECESSARY))

        @JvmStatic
        fun zero(): Quantity = ZERO
    }

    override fun compareTo(other: Quantity): Int =
        value.compareTo(other.value)

    override fun toString(): String =
        value.toPlainString()
}
