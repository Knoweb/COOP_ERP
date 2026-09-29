package lk.coopfed.knoweb.till.core.receipt

/**
 * A 1-bit image at the printer's dot width (576 dots for 80 mm, 384 for 58 mm), packed eight dots a
 * byte, most significant bit first, a set bit printed black: the layout ESC/POS GS v 0 takes as is.
 */
class MonoBitmap(val width: Int, val height: Int, val bits: ByteArray = ByteArray(((width + 7) / 8) * height)) {
    val bytesPerRow: Int get() = (width + 7) / 8

    init {
        require(bits.size == bytesPerRow * height) { "The bits do not fit $width x $height" }
    }

    operator fun get(x: Int, y: Int): Boolean =
        (bits[y * bytesPerRow + x / 8].toInt() shr (7 - x % 8)) and 1 == 1

    operator fun set(x: Int, y: Int, black: Boolean) {
        val i = y * bytesPerRow + x / 8
        val mask = 1 shl (7 - x % 8)
        bits[i] = (if (black) bits[i].toInt() or mask else bits[i].toInt() and mask.inv()).toByte()
    }

    fun blackCount(): Int = bits.sumOf { it.toInt().and(0xff).countOneBits() }

    companion object {
        const val DOTS_80MM = 576
        const val DOTS_58MM = 384
    }
}
