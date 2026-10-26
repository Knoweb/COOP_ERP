package lk.coopfed.knoweb.till.peripherals

import kotlin.time.Clock

/**
 * Advanced Barcode Scanning Layer (doc 26 section 3.6, doc 26A section 7; ticket T-05).
 *
 * Implements symbology detection, GS1-128, DataMatrix and Digital Link parsing, weighed-label
 * parsing (prefixes 20–29), and dual-mark deduplication.
 */
class ScanLayer(
    private val dedupeMillis: Long = 700,
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val referenceYear: () -> Int = { 2026 },
) {
    private var lastGtin: String? = null
    private var lastSymbology: Symbology? = null
    private var lastScanAt: Long = 0L

    fun parse(raw: RawScan, atMillis: Long = clockMillis()): ScanResult? {
        val trimmed = raw.data.trim()
        if (trimmed.isEmpty()) return null

        // 1. Check AIM prefix: ]E0, ]E4, ]C1, ]d2, ]Q3
        var aimSymbology: Symbology? = null
        var cleanData = trimmed
        var isAimPrefixed = false
        if (cleanData.startsWith("]") && cleanData.length >= 3) {
            isAimPrefixed = true
            val aim = cleanData.substring(0, 3)
            aimSymbology = when (aim) {
                "]E0", "]E4" -> Symbology.EAN13
                "]C1" -> Symbology.GS1_128
                "]d2" -> Symbology.GS1_DATAMATRIX
                "]Q3" -> Symbology.GS1_QR
                else -> null
            }
            if (aimSymbology != null) {
                cleanData = cleanData.substring(3).trim()
            }
        }

        if (isAimPrefixed && aimSymbology == null) {
            return ScanResult(
                symbology = Symbology.UNKNOWN,
                rawData = trimmed,
                isMalformed = true,
                error = "Unsupported AIM symbology identifier \"${trimmed.take(3)}\"",
            )
        }

        val explicitSymbology = raw.symbology ?: aimSymbology
        val detected = explicitSymbology ?: detect(cleanData)

        val result: ScanResult = when (detected) {
            Symbology.EAN13, Symbology.EAN8, Symbology.UPCA -> {
                if (GtinMath.isValid(cleanData)) {
                    val actualSym = when (cleanData.length) {
                        8 -> Symbology.EAN8
                        12 -> Symbology.UPCA
                        else -> Symbology.EAN13
                    }
                    ScanResult(
                        symbology = actualSym,
                        rawData = cleanData,
                        gtin = GtinMath.normalize14(cleanData),
                    )
                } else {
                    ScanResult(
                        symbology = detected,
                        rawData = cleanData,
                        isMalformed = true,
                        error = "Invalid GTIN check digit in barcode \"$cleanData\"",
                    )
                }
            }
            Symbology.INTERNAL_20_29 -> {
                WeighedLabelParser.parse(cleanData)
                    ?: ScanResult(
                        symbology = Symbology.INTERNAL_20_29,
                        rawData = cleanData,
                        isMalformed = true,
                        error = "Malformed internal weighed label \"$cleanData\"",
                    )
            }
            Symbology.GS1_128, Symbology.GS1_DATAMATRIX, Symbology.GS1_QR -> {
                Gs1Parser.parse(cleanData, detected, referenceYear())
            }
            Symbology.UNKNOWN -> {
                ScanResult(Symbology.UNKNOWN, rawData = cleanData)
            }
        }

        // Deduplication only applies to valid scans
        if (!result.isMalformed && isDuplicate(result, atMillis)) {
            return null
        }

        if (!result.isMalformed) {
            recordScan(result, atMillis)
        }
        return result
    }

    fun parse(data: String, atMillis: Long = clockMillis()): ScanResult? =
        parse(RawScan(data), atMillis)

    /**
     * Deduplication logic (doc 26A section 7 line 396; ticket T-05):
     * "a second read of the same GTIN within 700 ms from a different symbology (dual-marked pack) is dropped; one beep".
     *
     * Legitimate repeated scans of the same product (same symbology, e.g. two physical cans scanned in rapid succession)
     * are NOT dropped here. Scanner hardware bounce cannot be reliably distinguished from intentional rapid scans with
     * standard scanner event metadata; dropping identical codes would silently drop legitimate customer purchases.
     * Hardware-level bounce suppression is handled by scanner firmware (same-symbol delay) or keyboard wedge buffers.
     */
    private fun isDuplicate(result: ScanResult, atMillis: Long): Boolean {
        val dt = atMillis - lastScanAt
        if (dt < 0 || dt >= dedupeMillis) return false

        // Dual-mark deduplication:
        // A second read of the same GTIN within 700 ms from a different symbology (dual-marked pack) is dropped
        if (result.gtin != null && lastGtin != null && result.gtin == lastGtin) {
            if (result.symbology != lastSymbology) {
                return true
            }
        }

        return false
    }

    private fun recordScan(result: ScanResult, atMillis: Long) {
        lastGtin = result.gtin
        lastSymbology = result.symbology
        lastScanAt = atMillis
    }

    private fun detect(data: String): Symbology {
        if (data.startsWith("http://") || data.startsWith("https://")) {
            return Symbology.GS1_QR
        }
        if (data.contains(Gs1Parser.GROUP_SEPARATOR) || data.startsWith("(01)")) {
            return Symbology.GS1_DATAMATRIX
        }
        if (GtinMath.allDigits(data)) {
            if (data.length == 13) {
                val prefix = data.substring(0, 2).toIntOrNull()
                if (prefix in 20..29) {
                    return Symbology.INTERNAL_20_29
                }
                return Symbology.EAN13
            }
            if (data.length == 12) {
                return Symbology.UPCA
            }
            if (data.length == 8) {
                return Symbology.EAN8
            }
            if (data.length == 14) {
                return Symbology.GS1_128
            }
        }
        return Symbology.UNKNOWN
    }
}
