package lk.coopfed.knoweb.till.peripherals

/**
 * A raw scan emitted by a physical scanner (or wedge buffer).
 *
 * [symbology] is set when provided by the scanner hardware, driver metadata, or AIM identifier.
 */
data class RawScan(
    val data: String,
    val symbology: Symbology? = null,
)
