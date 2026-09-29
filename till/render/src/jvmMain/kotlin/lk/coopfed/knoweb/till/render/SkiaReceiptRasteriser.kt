package lk.coopfed.knoweb.till.render

import lk.coopfed.knoweb.till.core.receipt.Align
import lk.coopfed.knoweb.till.core.receipt.Block
import lk.coopfed.knoweb.till.core.receipt.MonoBitmap
import lk.coopfed.knoweb.till.core.receipt.ReceiptLayout
import lk.coopfed.knoweb.till.core.receipt.TextSize
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontEdging
import org.jetbrains.skia.FontHinting
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import org.jetbrains.skia.paragraph.TypefaceFontProvider

/**
 * The desktop rasteriser: Skia's paragraph engine (SkParagraph, HarfBuzz shaping) with only the
 * bundled Noto fonts (the system's fonts are never used, so every till prints the same glyphs),
 * drawn in grey and thresholded to 1 bit. Latin, Sinhala and Tamil may mix in one line; each run
 * takes the first bundled family that has its characters.
 */
class SkiaReceiptRasteriser : ReceiptRasteriser {

    private val fonts: FontCollection = FontCollection().apply {
        val provider = TypefaceFontProvider()
        for ((file, family) in FONT_FILES) {
            val bytes = SkiaReceiptRasteriser::class.java.getResourceAsStream("/fonts/$file")?.use { it.readBytes() }
                ?: error("The bundled font $file is missing from the till's resources")
            provider.registerTypeface(FontMgr.default.makeFromData(Data.makeFromBytes(bytes)), family)
        }
        setAssetFontManager(provider)
        setEnableFallback(false)
    }

    /** How many characters of the last slip no bundled font could draw (0 on a correct slip). */
    var lastUnresolvedGlyphs: Int = 0
        private set

    override fun rasterise(layout: ReceiptLayout, widthDots: Int): MonoBitmap {
        val width = widthDots - 2 * MARGIN
        lastUnresolvedGlyphs = 0
        val placed = mutableListOf<Placed>()
        var y = MARGIN.toFloat()
        for (block in layout.blocks) {
            when (block) {
                is Block.Text -> {
                    val p = paragraph(block.text, block.size, block.bold, block.align, width.toFloat())
                    placed += Placed.Text(p, MARGIN.toFloat(), y)
                    y += p.height
                }
                is Block.Row -> {
                    val right = paragraph(block.right, block.size, block.bold, Align.END, width.toFloat())
                    val rightWidth = right.maxIntrinsicWidth.coerceAtMost(width * 0.6f)
                    val left = paragraph(block.left, block.size, block.bold, Align.START, width - rightWidth - GAP)
                    right.layout(width.toFloat())
                    placed += Placed.Text(left, MARGIN.toFloat(), y)
                    placed += Placed.Text(right, MARGIN.toFloat(), y)
                    y += maxOf(left.height, right.height)
                }
                Block.Rule -> {
                    placed += Placed.Rule(y + 6)
                    y += 14
                }
                is Block.Gap -> y += block.dots
            }
        }
        val height = (y + MARGIN).toInt()

        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo(widthDots, height, ColorType.GRAY_8, ColorAlphaType.OPAQUE))
        val canvas = Canvas(bitmap)
        canvas.clear(WHITE)
        val ink = Paint().apply { color = BLACK; mode = PaintMode.STROKE; strokeWidth = 2f }
        for (item in placed) {
            when (item) {
                is Placed.Text -> item.paragraph.paint(canvas, item.x, item.y)
                is Placed.Rule -> {
                    var x = MARGIN.toFloat()
                    while (x < widthDots - MARGIN) {
                        canvas.drawLine(x, item.y, minOf(x + 8f, (widthDots - MARGIN).toFloat()), item.y, ink)
                        x += 12f
                    }
                }
            }
        }
        val grey = bitmap.readPixels() ?: error("Could not read the rendered slip")
        val out = MonoBitmap(widthDots, height)
        for (row in 0 until height) {
            for (x in 0 until widthDots) {
                if ((grey[row * widthDots + x].toInt() and 0xFF) < THRESHOLD) out[x, row] = true
            }
        }
        placed.filterIsInstance<Placed.Text>().forEach { it.paragraph.close() }
        bitmap.close()
        return out
    }

    private fun paragraph(text: String, size: TextSize, bold: Boolean, align: Align, width: Float): Paragraph {
        val style = TextStyle().apply {
            fontFamilies = FAMILIES
            fontSize = when (size) {
                TextSize.SMALL -> 20f
                TextSize.NORMAL -> 24f
                TextSize.LARGE -> 32f
            }
            color = BLACK
            fontStyle = if (bold) FontStyle.BOLD else FontStyle.NORMAL
            fontEdging = FontEdging.ANTI_ALIAS
            fontHinting = FontHinting.NONE
            subpixel = false
        }
        val paragraphStyle = ParagraphStyle().apply {
            textStyle = style
            alignment = when (align) {
                Align.START -> Alignment.LEFT
                Align.CENTER -> Alignment.CENTER
                Align.END -> Alignment.RIGHT
            }
        }
        val paragraph = ParagraphBuilder(paragraphStyle, fonts).use { builder ->
            builder.pushStyle(style)
            builder.addText(text)
            builder.build()
        }
        paragraph.layout(width)
        lastUnresolvedGlyphs += paragraph.unresolvedGlyphsCount
        return paragraph
    }

    private sealed interface Placed {
        class Text(val paragraph: Paragraph, val x: Float, val y: Float) : Placed
        class Rule(val y: Float) : Placed
    }

    companion object {
        private const val MARGIN = 8
        private const val GAP = 12f
        private const val THRESHOLD = 150
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private val FAMILIES = arrayOf("Till Latin", "Till Sinhala", "Till Tamil")
        private val FONT_FILES = listOf(
            "NotoSans-Regular.ttf" to "Till Latin",
            "NotoSans-Bold.ttf" to "Till Latin",
            "NotoSansSinhala-Regular.ttf" to "Till Sinhala",
            "NotoSansSinhala-Bold.ttf" to "Till Sinhala",
            "NotoSansTamil-Regular.ttf" to "Till Tamil",
            "NotoSansTamil-Bold.ttf" to "Till Tamil",
        )
    }
}
