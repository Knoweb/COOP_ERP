package lk.coopfed.knoweb.till.peripherals

/**
 * GTIN arithmetic (GS1 General Specifications section 7.9).
 * Validates check digits and normalizes GTIN-8, GTIN-12 and GTIN-13 to 14-digit GTIN.
 */
object GtinMath {

    /** True for a string of 8, 12, 13 or 14 digits whose last digit is the valid check digit. */
    fun isValid(value: String?): Boolean {
        if (value == null) return false
        val len = value.length
        if (len != 8 && len != 12 && len != 13 && len != 14) return false
        if (!allDigits(value)) return false
        val expected = checkDigit(value.substring(0, len - 1))
        return expected == (value.last() - '0')
    }

    /** The check digit of a run of digits (the digits without the check digit). */
    fun checkDigit(digitsWithoutCheck: String): Int {
        var sum = 0
        var weight = 3
        for (i in digitsWithoutCheck.length - 1 downTo 0) {
            val d = digitsWithoutCheck[i] - '0'
            sum += d * weight
            weight = if (weight == 3) 1 else 3
        }
        return (10 - (sum % 10)) % 10
    }

    /** Normalizes a GTIN-8, GTIN-12, GTIN-13 or GTIN-14 to a 14-digit string with leading zeros. */
    fun normalize14(digits: String): String = digits.padStart(14, '0')

    /** A GTIN without its leading zeros: 04791234567891 and 4791234567891 are the same item. */
    fun withoutLeadingZeros(gtin: String): String {
        var start = 0
        while (start < gtin.length - 1 && gtin[start] == '0') {
            start++
        }
        return gtin.substring(start)
    }

    fun allDigits(s: String): Boolean {
        if (s.isEmpty()) return false
        for (i in 0 until s.length) {
            val c = s[i]
            if (c < '0' || c > '9') return false
        }
        return true
    }
}
