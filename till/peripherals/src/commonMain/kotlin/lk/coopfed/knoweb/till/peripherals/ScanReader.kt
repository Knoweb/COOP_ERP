package lk.coopfed.knoweb.till.peripherals

/**
 * A USB or Bluetooth scanner in keyboard mode types the barcode and an Enter, faster than a person
 * can (research report section 5.3). The sell screen keeps its scan field focused; this reader
 * turns the keystrokes into a scan and tells a scanned code from one typed by hand, so a typed code
 * can be confirmed differently. Shared by every platform.
 *
 * 26A section 7: a second read of the same code within [dedupeMillis] (a pack with two barcode
 * symbologies read twice) is dropped.
 */
class ScanReader(
    private val maxKeyGapMillis: Long = 50,
    private val dedupeMillis: Long = 700,
) {
    data class Scan(val code: String, val byScanner: Boolean)

    private val buffer = StringBuilder()
    private var firstKeyAt = 0L
    private var lastKeyAt = 0L
    private var slowKeys = 0
    private var lastScan: Scan? = null
    private var lastScanAt = 0L

    /** A printable key at [atMillis]. */
    fun key(char: Char, atMillis: Long) {
        if (buffer.isEmpty()) firstKeyAt = atMillis else if (atMillis - lastKeyAt > maxKeyGapMillis) slowKeys++
        buffer.append(char)
        lastKeyAt = atMillis
    }

    /** Enter at [atMillis]: the scan, or null for an empty buffer or a repeat within the dedupe window. */
    fun enter(atMillis: Long): Scan? {
        val code = buffer.toString().trim()
        val byScanner = code.length >= 6 && slowKeys == 0
        buffer.clear()
        slowKeys = 0
        if (code.isEmpty()) return null
        val scan = Scan(code, byScanner)
        if (byScanner && lastScan?.code == code && atMillis - lastScanAt < dedupeMillis) return null
        lastScan = scan
        lastScanAt = atMillis
        return scan
    }
}
