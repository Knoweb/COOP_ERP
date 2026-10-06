package lk.coopfed.knoweb.till.peripherals

import lk.coopfed.knoweb.till.core.receipt.MonoBitmap

/**
 * The few ESC/POS commands the till sends (research report section 5.1): Sinhala and Tamil are never
 * sent as text, because printer code pages cannot shape them; the whole slip goes as a raster image
 * (GS v 0), in bands so that a slow printer's buffer does not overflow, then a feed and a cut, and
 * the drawer kick after a cash sale.
 */
object EscPos {

    /** ESC @: reset the printer to its defaults. */
    val INITIALISE = byteArrayOf(0x1B, 0x40)

    /** ESC p 0 25 250: pulse drawer pin 2 for 50 ms on, 500 ms off. */
    val KICK_DRAWER = byteArrayOf(0x1B, 0x70, 0x00, 0x19, 0xFA.toByte())

    /** ESC d 4: feed four lines, so the last printed line clears the cutter. */
    val FEED = byteArrayOf(0x1B, 0x64, 0x04)

    /** GS V 1: partial cut. */
    val CUT = byteArrayOf(0x1D, 0x56, 0x01)

    /**
     * GS v 0 m xL xH yL yH d1...dk, one command per band of at most [bandHeight] rows.
     * m = 0 (normal density); x is the width in bytes, y the height in dots.
     */
    fun raster(bitmap: MonoBitmap, bandHeight: Int = 128): ByteArray {
        val out = ArrayList<Byte>(bitmap.bits.size + 64)
        val bytesPerRow = bitmap.bytesPerRow
        var top = 0
        while (top < bitmap.height) {
            val rows = minOf(bandHeight, bitmap.height - top)
            out += listOf(0x1D, 0x76, 0x30, 0x00).map { it.toByte() }
            out += (bytesPerRow and 0xFF).toByte()
            out += ((bytesPerRow shr 8) and 0xFF).toByte()
            out += (rows and 0xFF).toByte()
            out += ((rows shr 8) and 0xFF).toByte()
            val start = top * bytesPerRow
            for (i in start until start + rows * bytesPerRow) out += bitmap.bits[i]
            top += rows
        }
        return out.toByteArray()
    }

    /** A whole print job: reset, the image, feed, cut, and the drawer kick when asked. */
    fun job(bitmap: MonoBitmap, kickDrawer: Boolean): ByteArray =
        INITIALISE + raster(bitmap) + FEED + CUT + (if (kickDrawer) KICK_DRAWER else ByteArray(0))
}
