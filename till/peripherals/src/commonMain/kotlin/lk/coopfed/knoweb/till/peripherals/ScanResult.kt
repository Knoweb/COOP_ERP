package lk.coopfed.knoweb.till.peripherals

import kotlinx.datetime.LocalDate

/**
 * The typed result of parsing a barcode or 2D code (doc 26 section 3.6, doc 26A section 7).
 *
 * [weightGrams] is exact integer grams (e.g. 850 for 0.850 kg; 1500 for 1.500 kg).
 * Hardware-independent: uses only primitive types and [LocalDate]. Core converts to Qty using integer arithmetic.
 */
data class ScanResult(
    val symbology: Symbology,
    val rawData: String,
    val gtin: String? = null,
    val code: String? = null,
    val lot: String? = null,
    val expiry: LocalDate? = null,
    val weightGrams: Long? = null,
    val batchRef: String? = null,
    val isMalformed: Boolean = false,
    val error: String? = null,
)
