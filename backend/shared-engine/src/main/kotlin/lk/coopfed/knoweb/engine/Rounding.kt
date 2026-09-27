package lk.coopfed.knoweb.engine

import java.math.RoundingMode

/**
 * Cash rounding (doc 23 section 3.5 step 7; G-10): a cash tender rounds the receipt total to
 * the nearest rupee, half up (.49 down, .50 up); card and account tenders are exact. Rounding
 * is a receipt-level field; lines are never touched.
 */
object Rounding {

    /** The receipt total a cash customer pays. */
    @JvmStatic
    fun roundCash(value: Money): Money =
        Money.rounded(value.amount.setScale(0, RoundingMode.HALF_UP))

    /** The adjustment the receipt records for a cash tender: rounded total minus total. */
    @JvmStatic
    fun toRupee(total: Money): Money =
        roundCash(total) - total
}
