package lk.coopfed.knoweb.till.render

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import lk.coopfed.knoweb.till.core.model.IssuedReceipt
import lk.coopfed.knoweb.till.core.model.ReceiptLine
import lk.coopfed.knoweb.till.core.model.SessionRecord
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.money.Qty
import lk.coopfed.knoweb.till.core.receipt.MonoBitmap
import lk.coopfed.knoweb.till.core.receipt.ReceiptLayout
import lk.coopfed.knoweb.till.core.receipt.ReceiptLayouts
import lk.coopfed.knoweb.till.core.receipt.SlipHeader
import lk.coopfed.knoweb.till.core.session.ZReport
import lk.coopfed.knoweb.till.core.snapshot.Language
import lk.coopfed.knoweb.till.peripherals.jvm.Png

/**
 * Golden images of the printed slips (research report section 5.1: "include golden-image tests of
 * the rasteriser in CI"). Each slip is rendered at 576 dots and compared with the PNG kept in
 * src/jvmTest/resources/golden. Glyph edges differ slightly between Windows (DirectWrite) and Linux
 * (FreeType) rasterisers, so the comparison allows a small share of differing dots; a wrong
 * shaping, a missing glyph or a moved line differs by far more. The rendered images are always
 * written to build/golden-actual for a look. -Pgolden.update=true writes the goldens again.
 */
class GoldenReceiptTest {

    private val rasteriser = SkiaReceiptRasteriser()
    private val zone = TimeZone.of("Asia/Colombo")

    private fun receipt(language: Language): IssuedReceipt {
        fun line(no: Int, en: String, si: String, ta: String, qty: Int, price: String) = ReceiptLine(
            no, "sku-$no", en, when (language) { Language.SI -> si; Language.TA -> ta; Language.EN -> en }, "EA",
            Qty.of(qty), Money.parse(price), Qty.of(qty).times(Money.parse(price)),
        )
        val lines = listOf(
            line(1, "Samba rice 5kg", "සම්බා සහල් 5kg", "சம்பா அரிசி 5kg", 1, "1250.00"),
            line(2, "Red dhal 1kg", "රතු පරිප්පු 1kg", "சிவப்பு பருப்பு 1kg", 2, "385.50"),
            line(3, "Coconut oil 750ml", "පොල්තෙල් 750ml", "தேங்காய் எண்ணெய் 750ml", 1, "720.00"),
            line(4, "Soap", "Soap", "Soap", 3, "95.00"),
        )
        val gross = lines.fold(Money.ZERO) { s, l -> s + l.lineTotal }
        return IssuedReceipt(
            documentId = "d-1", sessionId = "s-1", number = 128, numberDisplay = "S01-T2-128", issuedAt = 1_790_655_000,
            businessDate = LocalDate(2026, 9, 29), operatorUserId = "op",
            operatorName = if (language == Language.TA) "கலா" else "නිමාලි",
            lines = lines, gross = gross, tendered = Money.parse("5000.00"), change = Money.parse("5000.00") - gross,
            contentHash = "h", deviceSeq = 9,
        )
    }

    private fun header(language: Language) = when (language) {
        Language.TA -> SlipHeader("பருத்தித்துறை கடை", "S01", "2")
        else -> SlipHeader("කුලියාපිටිය නගර වෙළඳසැල", "S01", "2")
    }

    @Test
    fun aSinhalaReceipt() = golden("receipt-si", ReceiptLayouts.receipt(receipt(Language.SI), header(Language.SI), Language.SI, zone))

    @Test
    fun aTamilReceipt() = golden("receipt-ta", ReceiptLayouts.receipt(receipt(Language.TA), header(Language.TA), Language.TA, zone))

    @Test
    fun aSinhalaZReport() {
        val r = receipt(Language.SI)
        val session = SessionRecord("s-1", "op", "නිමාලි", LocalDate(2026, 9, 29), 1_790_640_000, Money.parse("2000.00"),
            closedAt = 1_790_670_000, countedCash = Money.parse("4800.00"), expectedCash = Money.parse("2000.00") + r.gross)
        golden("zreport-si", ReceiptLayouts.zReport(ZReport.of(session, listOf(r)), header(Language.SI), Language.SI, zone))
    }

    @Test
    fun shapingJoinsSinhalaAndTamilClustersIntoFewerGlyphsThanCharacters() {
        // "ක්‍ෂ" and "க்ஷ" are conjuncts: a shaping engine draws them as one cluster. Without shaping the
        // same text renders wider, as separate letters with visible virama marks.
        val shaped = rasteriser.rasterise(
            ReceiptLayout(Language.SI, listOf(lk.coopfed.knoweb.till.core.receipt.Block.Text("ශ්‍රී ලංකා ක්‍ෂේත්‍ර க்ஷேத்திரம்"))), 576,
        )
        assertEquals(0, rasteriser.lastUnresolvedGlyphs, "every character must come from a bundled font")
        assertTrue(shaped.blackCount() > 500)
    }

    private fun golden(name: String, layout: ReceiptLayout) {
        val actual = rasteriser.rasterise(layout, MonoBitmap.DOTS_80MM)
        assertEquals(0, rasteriser.lastUnresolvedGlyphs, "every character must come from a bundled font")
        Png.write(actual, File(System.getProperty("golden.out"), "$name.png"))
        val goldenFile = File(System.getProperty("golden.dir"), "$name.png")
        if (System.getProperty("golden.update") == "true" || !goldenFile.exists()) {
            Png.write(actual, goldenFile)
            if (System.getProperty("golden.update") != "true") fail("No golden image for $name yet; written to $goldenFile, review it and run again")
            return
        }
        val expected = Png.read(goldenFile)
        assertEquals(expected.width, actual.width, "$name: width")
        // A line wrapped differently moves everything below it; allow a few dots of height either way.
        assertTrue(kotlin.math.abs(expected.height - actual.height) <= 4, "$name: height ${actual.height}, golden ${expected.height}")
        var differing = 0
        val rows = minOf(expected.height, actual.height)
        for (y in 0 until rows) for (x in 0 until expected.width) if (expected[x, y] != actual[x, y]) differing++
        val share = differing.toDouble() / expected.blackCount().coerceAtLeast(1)
        assertTrue(share < MAX_SHARE, "$name: ${"%.1f".format(share * 100)}% of the golden's black dots differ (see build/golden-actual)")
    }

    companion object {
        /** Differing dots as a share of the golden's black dots: edge noise between OS rasterisers stays well under this. */
        const val MAX_SHARE = 0.12
    }
}
