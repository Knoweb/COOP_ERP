package lk.coopfed.knoweb.engine

import java.math.BigDecimal
import java.math.RoundingMode

/** A tax rate in force (M2's tax category and rate); the rate is a percentage, 18.00 for 18 %. */
data class Tax(
    val code: String,
    val rate: BigDecimal
) {

    companion object {

        /**
         * The tax contained in a tax-inclusive amount (doc 23 section 3.5 step 7: retail prices
         * are tax-inclusive; the tax is decomposed from them by the rate in force):
         * gross × rate / (100 + rate), rounded half up to the cent.
         */
        @JvmStatic
        fun inclusivePart(gross: Money, ratePercent: BigDecimal): Money {
            if (ratePercent.signum() == 0) {
                return Money.ZERO
            }
            val tax = gross.amount
                .multiply(ratePercent)
                .divide(BigDecimal(100).add(ratePercent), 2, RoundingMode.HALF_UP)
            return Money.rounded(tax)
        }

        /** The tax on a tax-exclusive amount (trade prices, 23 section 3.1): net × rate / 100. */
        @JvmStatic
        fun exclusivePart(net: Money, ratePercent: BigDecimal): Money =
            net.percent(ratePercent)
    }
}
