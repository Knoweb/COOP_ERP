package lk.coopfed.knoweb.till.render

import lk.coopfed.knoweb.till.core.receipt.MonoBitmap
import lk.coopfed.knoweb.till.core.receipt.ReceiptLayout

/**
 * Draws a slip's layout as a 1-bit image [widthDots] wide (576 for 80 mm, 384 for 58 mm), with the
 * bundled Noto fonts and a shaping text engine, so Sinhala and Tamil print correctly on any
 * ESC/POS printer (doc 26 section 3.4; CR-30-1 point 3). One implementation per platform text stack.
 */
fun interface ReceiptRasteriser {
    fun rasterise(layout: ReceiptLayout, widthDots: Int): MonoBitmap
}
