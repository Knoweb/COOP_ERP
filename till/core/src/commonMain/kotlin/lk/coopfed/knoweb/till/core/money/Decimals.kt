package lk.coopfed.knoweb.till.core.money

import kotlin.math.abs

/**
 * Fixed-point decimals for the till, held as whole numbers of the smallest unit so that common code
 * needs no BigDecimal and never uses a double. Money is in cents (numeric(14,2)), a quantity in
 * thousandths (numeric(14,3)), as AGENTS.md fixes the column scales.
 */
object Decimals {

    /** Parses "12", "12.5" or "-3.125" at [scale]; more decimals than the scale round half up (away from zero). */
    fun parse(text: String, scale: Int): Long {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "Not a number: '$text'" }
        val negative = trimmed.startsWith("-")
        val digits = trimmed.removePrefix("-").removePrefix("+")
        val parts = digits.split('.')
        require(parts.size <= 2 && parts.all { p -> p.all { it.isDigit() } } && digits.any { it.isDigit() }) {
            "Not a number: '$text'"
        }
        val whole = parts[0].ifEmpty { "0" }.toLong()
        val fraction = parts.getOrElse(1) { "" }
        var unscaled = whole * pow10(scale)
        val kept = fraction.take(scale).padEnd(scale, '0')
        if (scale > 0) unscaled += kept.toLong()
        if (fraction.length > scale && fraction[scale] >= '5') unscaled += 1
        return if (negative) -unscaled else unscaled
    }

    /** "12.50" for 1250 at scale 2: the plain text with all decimals, as a payload carries it. */
    fun plain(unscaled: Long, scale: Int): String {
        if (scale == 0) return unscaled.toString()
        val sign = if (unscaled < 0) "-" else ""
        val magnitude = abs(unscaled)
        val whole = magnitude / pow10(scale)
        val fraction = (magnitude % pow10(scale)).toString().padStart(scale, '0')
        return "$sign$whole.$fraction"
    }

    /**
     * The kernel's canonical text of a decimal (ContentHash): trailing zeros stripped, zero as "0",
     * so that 10.0 and 10.00 hash alike.
     */
    fun canonical(unscaled: Long, scale: Int): String {
        if (unscaled == 0L) return "0"
        val text = plain(unscaled, scale)
        return if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
    }

    /** a × b / 10^divideScale, rounded half away from zero. */
    fun multiply(a: Long, b: Long, divideScale: Int): Long {
        val product = a * b
        val divisor = pow10(divideScale)
        val quotient = product / divisor
        val remainder = abs(product % divisor)
        return if (remainder * 2 >= divisor) quotient + (if (product < 0) -1 else 1) else quotient
    }

    fun pow10(n: Int): Long {
        var r = 1L
        repeat(n) { r *= 10 }
        return r
    }
}

/** An amount of LKR in cents. */
@kotlinx.serialization.Serializable
data class Money(val cents: Long) : Comparable<Money> {
    operator fun plus(other: Money) = Money(cents + other.cents)
    operator fun minus(other: Money) = Money(cents - other.cents)
    override fun compareTo(other: Money) = cents.compareTo(other.cents)

    /** "1250.00" */
    fun plain(): String = Decimals.plain(cents, 2)

    /** "1,250.00", for the screen and the paper. */
    fun display(): String {
        val text = plain()
        val negative = text.startsWith("-")
        val (whole, fraction) = text.removePrefix("-").split('.')
        val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
        return (if (negative) "-" else "") + grouped + "." + fraction
    }

    override fun toString() = plain()

    companion object {
        val ZERO = Money(0)
        fun parse(text: String) = Money(Decimals.parse(text, 2))
    }
}

/** A quantity in thousandths of its unit (1 each = 1000). */
@kotlinx.serialization.Serializable
data class Qty(val milli: Long) {
    fun plain(): String = Decimals.plain(milli, 3)

    /** "2" for 2000, "0.25" for 250: the short form for the screen and the paper. */
    fun display(): String = Decimals.canonical(milli, 3)

    operator fun plus(other: Qty) = Qty(milli + other.milli)

    /** qty × unit price, rounded to the cent. */
    fun times(price: Money): Money = Money(Decimals.multiply(milli, price.cents, 3))

    companion object {
        val ONE = Qty(1000)
        fun parse(text: String) = Qty(Decimals.parse(text, 3))
        fun of(units: Int) = Qty(units * 1000L)
    }
}
