package lk.coopfed.knoweb.engine

import java.math.BigDecimal
import java.math.RoundingMode

data class Tax private constructor(
    val code: String,
    val ratePercent: BigDecimal
) {

    companion object {

        @JvmStatic
        fun of(
            code: String,
            ratePercent: String
        ): Tax =
            Tax(
                code = code,
                ratePercent =
                    BigDecimal(ratePercent)
                        .setScale(2, RoundingMode.UNNECESSARY)
            )
    }
}