package lk.coopfed.knoweb.till.peripherals

import kotlinx.datetime.LocalDate

/**
 * Parses GS1 Element Strings (with group separator U+001D or brackets) and GS1 Digital Link URIs
 * (doc 26 section 3.6, doc 26A section 7).
 *
 * Supports Application Identifiers:
 * - 01: GTIN-14 (fixed 14 digits, validated with check digit)
 * - 10: Batch/lot (variable length up to 20 characters)
 * - 17: Expiry date (fixed 6 digits YYMMDD, day 00 = end of month)
 * - 3103: Net weight in kilograms with 3 decimal places (fixed 6 digits = grams)
 */
object Gs1Parser {

    const val GROUP_SEPARATOR = '\u001D'

    fun parse(data: String, defaultSymbology: Symbology, currentYear: Int = 2026): ScanResult {
        val trimmed = data.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return parseDigitalLink(trimmed, defaultSymbology, currentYear)
        }
        return parseElementString(trimmed, defaultSymbology, currentYear)
    }

    private fun parseDigitalLink(url: String, defaultSymbology: Symbology, currentYear: Int): ScanResult {
        var gtin: String? = null
        var lot: String? = null
        var expiryStr: String? = null
        var weightStr: String? = null

        val queryStart = url.indexOf('?')
        val pathPart = if (queryStart >= 0) url.substring(0, queryStart) else url
        val queryPart = if (queryStart >= 0) url.substring(queryStart + 1) else null

        // Parse path segments: .../01/{gtin}/10/{lot}/17/{yymmdd}/3103/{weight}
        val segments = pathPart.split('/').filter { it.isNotEmpty() }
        var i = 0
        while (i < segments.size) {
            when (segments[i]) {
                "01" -> if (i + 1 < segments.size) { gtin = segments[i + 1]; i++ }
                "10" -> if (i + 1 < segments.size) { lot = segments[i + 1]; i++ }
                "17" -> if (i + 1 < segments.size) { expiryStr = segments[i + 1]; i++ }
                "3103" -> if (i + 1 < segments.size) { weightStr = segments[i + 1]; i++ }
            }
            i++
        }

        // Parse query parameters
        if (!queryPart.isNullOrEmpty()) {
            val pairs = queryPart.split('&')
            for (pair in pairs) {
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val key = pair.substring(0, eq).trim()
                    val value = pair.substring(eq + 1).trim()
                    when (key) {
                        "01" -> if (gtin == null) gtin = value
                        "10" -> if (lot == null) lot = value
                        "17" -> if (expiryStr == null) expiryStr = value
                        "3103" -> if (weightStr == null) weightStr = value
                    }
                }
            }
        }

        val symbology = if (defaultSymbology != Symbology.UNKNOWN) defaultSymbology else Symbology.GS1_QR

        if (gtin == null) {
            return ScanResult(symbology, rawData = url, isMalformed = true, error = "GS1 Digital Link URI missing GTIN (AI 01)")
        }

        gtin = GtinMath.normalize14(gtin)
        if (!GtinMath.isValid(gtin)) {
            return ScanResult(symbology, rawData = url, isMalformed = true, error = "Invalid GTIN check digit in GS1 Digital Link URI: \"$gtin\"")
        }

        val expiry = if (expiryStr != null) {
            val parsed = parseExpiry(expiryStr, currentYear)
            if (parsed == null) {
                return ScanResult(symbology, rawData = url, isMalformed = true, error = "Invalid expiry date in GS1 Digital Link URI: \"$expiryStr\"")
            }
            parsed
        } else null

        val weightGrams = if (weightStr != null) {
            val parsed = if (GtinMath.allDigits(weightStr)) weightStr.toLongOrNull() else null
            if (parsed == null || parsed <= 0L) {
                return ScanResult(symbology, rawData = url, isMalformed = true, error = "Invalid net weight in GS1 Digital Link URI: \"$weightStr\"")
            }
            parsed
        } else null

        if (lot != null && (lot.isEmpty() || lot.length > 20)) {
            return ScanResult(symbology, rawData = url, isMalformed = true, error = "Invalid lot in GS1 Digital Link URI")
        }

        return ScanResult(
            symbology = symbology,
            rawData = url,
            gtin = gtin,
            lot = lot,
            expiry = expiry,
            weightGrams = weightGrams,
        )
    }

    private fun parseElementString(data: String, defaultSymbology: Symbology, currentYear: Int): ScanResult {
        var gtin: String? = null
        var lot: String? = null
        var expiryStr: String? = null
        var weightStr: String? = null
        val resolvedSymbology = if (defaultSymbology != Symbology.UNKNOWN) defaultSymbology else Symbology.GS1_DATAMATRIX

        if (data.startsWith('(')) {
            // Bracketed AI format: (01)04791234567891(10)LOT123...
            var pos = 0
            while (pos < data.length) {
                if (data[pos] != '(') {
                    return ScanResult(resolvedSymbology, rawData = data, isMalformed = true, error = "Malformed bracketed GS1 element string")
                }
                val close = data.indexOf(')', pos)
                if (close < 0) {
                    return ScanResult(resolvedSymbology, rawData = data, isMalformed = true, error = "Unclosed bracket in GS1 element string")
                }
                val ai = data.substring(pos + 1, close)
                val nextOpen = data.indexOf('(', close + 1)
                val value = if (nextOpen >= 0) data.substring(close + 1, nextOpen) else data.substring(close + 1)
                when (ai) {
                    "01" -> gtin = value
                    "10" -> lot = value
                    "17" -> expiryStr = value
                    "3103" -> weightStr = value
                }
                pos = if (nextOpen >= 0) nextOpen else data.length
            }
        } else {
            // Group separator format: 010479123456789110LOT123\u001D172612313103001500
            var pos = 0
            while (pos < data.length) {
                if (data[pos] == GROUP_SEPARATOR) {
                    pos++
                    continue
                }
                // Try 4-digit AI: 3103
                if (pos + 4 <= data.length && data.substring(pos, pos + 4) == "3103") {
                    pos += 4
                    if (pos + 6 <= data.length) {
                        weightStr = data.substring(pos, pos + 6)
                        pos += 6
                        continue
                    } else {
                        return ScanResult(resolvedSymbology, rawData = data, isMalformed = true, error = "Incomplete weight value for AI 3103")
                    }
                }
                // Try 2-digit AIs: 01, 10, 17
                if (pos + 2 > data.length) {
                    return ScanResult(resolvedSymbology, rawData = data, isMalformed = true, error = "Truncated GS1 Application Identifier")
                }
                val ai = data.substring(pos, pos + 2)
                pos += 2
                when (ai) {
                    "01" -> {
                        if (pos + 14 <= data.length) {
                            gtin = data.substring(pos, pos + 14)
                            pos += 14
                        } else {
                            return ScanResult(resolvedSymbology, rawData = data, isMalformed = true, error = "Incomplete GTIN-14 value for AI 01")
                        }
                    }
                    "17" -> {
                        if (pos + 6 <= data.length) {
                            expiryStr = data.substring(pos, pos + 6)
                            pos += 6
                        } else {
                            return ScanResult(resolvedSymbology, rawData = data, isMalformed = true, error = "Incomplete expiry value for AI 17")
                        }
                    }
                    "10" -> {
                        val end = data.indexOf(GROUP_SEPARATOR, pos)
                        val value = if (end >= 0) data.substring(pos, end) else data.substring(pos)
                        lot = value
                        pos = if (end >= 0) end + 1 else data.length
                    }
                    else -> {
                        return ScanResult(resolvedSymbology, rawData = data, isMalformed = true, error = "Unrecognized GS1 Application Identifier \"$ai\"")
                    }
                }
            }
        }

        if (gtin == null) {
            return ScanResult(resolvedSymbology, rawData = data, isMalformed = true, error = "GS1 element string missing GTIN (AI 01)")
        }

        gtin = GtinMath.normalize14(gtin)
        if (!GtinMath.isValid(gtin)) {
            return ScanResult(resolvedSymbology, rawData = data, isMalformed = true, error = "Invalid GTIN check digit in GS1 element string: \"$gtin\"")
        }

        val expiry = if (expiryStr != null) {
            val parsed = parseExpiry(expiryStr, currentYear)
            if (parsed == null) {
                return ScanResult(resolvedSymbology, rawData = data, isMalformed = true, error = "Invalid expiry date in GS1 AI 17: \"$expiryStr\"")
            }
            parsed
        } else null

        val weightGrams = if (weightStr != null) {
            val parsed = if (weightStr.length == 6 && GtinMath.allDigits(weightStr)) weightStr.toLongOrNull() else null
            if (parsed == null || parsed <= 0L) {
                return ScanResult(resolvedSymbology, rawData = data, isMalformed = true, error = "Invalid net weight in GS1 AI 3103: \"$weightStr\"")
            }
            parsed
        } else null

        if (lot != null && (lot.isEmpty() || lot.length > 20)) {
            return ScanResult(resolvedSymbology, rawData = data, isMalformed = true, error = "Invalid lot in GS1 AI 10")
        }

        return ScanResult(
            symbology = resolvedSymbology,
            rawData = data,
            gtin = gtin,
            lot = lot,
            expiry = expiry,
            weightGrams = weightGrams,
        )
    }

    fun parseExpiry(yymmdd: String, currentYear: Int): LocalDate? {
        if (yymmdd.length != 6 || !GtinMath.allDigits(yymmdd)) return null
        val yy = yymmdd.substring(0, 2).toInt()
        val month = yymmdd.substring(2, 4).toInt()
        val day = yymmdd.substring(4, 6).toInt()
        if (month !in 1..12) return null

        val century = currentYear - currentYear % 100
        var year = century + yy
        if (year - currentYear > 49) {
            year -= 100
        } else if (currentYear - year > 50) {
            year += 100
        }

        val maxDays = daysInMonth(year, month)
        val actualDay = if (day == 0) maxDays else day
        if (actualDay !in 1..maxDays) return null

        return try {
            LocalDate(year, month, actualDay)
        } catch (_: Exception) {
            null
        }
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeapYear(year)) 29 else 28
        else -> 30
    }

    private fun isLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
}
