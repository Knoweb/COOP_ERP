package lk.coopfed.knoweb.engine

import java.math.BigDecimal
import java.math.RoundingMode

data class Quantity private constructor(
    val value: BigDecimal
) {

    companion object {

        @JvmStatic
        fun of(value: String): Quantity =
            Quantity(
                BigDecimal(value)
                    .setScale(3, RoundingMode.UNNECESSARY)
            )

        @JvmStatic
        fun zero(): Quantity =
            Quantity(BigDecimal.ZERO.setScale(3))
    }

    override fun toString(): String =
        value.toPlainString()
}