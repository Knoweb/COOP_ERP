package lk.coopfed.knoweb.till.peripherals

/**
 * In-store weighed labels and stickers with GS1 prefixes 20–29 (doc 22 section 3.3, doc 26A section 7).
 *
 * Encodes 6-digit SKU code and 4-digit tail (weight in grams for 20..24; batch reference for 25..29).
 * Validates the EAN-13 check digit. Never encodes a price.
 */
object WeighedLabelParser {

    fun parse(data: String): ScanResult? {
        val trimmed = data.trim()
        if (trimmed.length != 13 || !GtinMath.allDigits(trimmed)) return null

        val prefix = trimmed.substring(0, 2).toIntOrNull() ?: return null
        if (prefix !in 20..29) return null

        if (!GtinMath.isValid(trimmed)) {
            return ScanResult(
                symbology = Symbology.INTERNAL_20_29,
                rawData = trimmed,
                isMalformed = true,
                error = "Invalid check digit in weighed label \"$trimmed\"",
            )
        }

        val code = trimmed.substring(2, 8)
        val tail = trimmed.substring(8, 12)

        return when (prefix) {
            in 20..24 -> {
                val grams = tail.toLongOrNull()
                if (grams == null || grams <= 0L) {
                    ScanResult(
                        symbology = Symbology.INTERNAL_20_29,
                        rawData = trimmed,
                        code = code,
                        isMalformed = true,
                        error = "Weighed label has invalid or zero weight",
                    )
                } else {
                    ScanResult(
                        symbology = Symbology.INTERNAL_20_29,
                        rawData = trimmed,
                        code = code,
                        weightGrams = grams,
                    )
                }
            }
            in 25..29 -> {
                ScanResult(
                    symbology = Symbology.INTERNAL_20_29,
                    rawData = trimmed,
                    code = code,
                    batchRef = tail,
                )
            }
            else -> null
        }
    }
}
