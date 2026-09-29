package lk.coopfed.knoweb.till.core.receipt

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import lk.coopfed.knoweb.till.core.model.IssuedReceipt
import lk.coopfed.knoweb.till.core.snapshot.Language
import lk.coopfed.knoweb.till.core.session.ZReport
import lk.coopfed.knoweb.till.core.time.Times

enum class Align { START, CENTER, END }
enum class TextSize { SMALL, NORMAL, LARGE }

/** One block of a printed slip, top to bottom. The rasteriser draws it; nothing here knows about pixels. */
sealed interface Block {
    data class Text(val text: String, val align: Align = Align.START, val size: TextSize = TextSize.NORMAL, val bold: Boolean = false) : Block
    /** A label on the left (wrapping if long) and a value on the right, e.g. an item and its amount. */
    data class Row(val left: String, val right: String, val size: TextSize = TextSize.NORMAL, val bold: Boolean = false) : Block
    data object Rule : Block
    data class Gap(val dots: Int) : Block
}

/** A slip (receipt, Z-report) as blocks, in the language it prints in. */
data class ReceiptLayout(val language: Language, val blocks: List<Block>)

/** What the paper says about the shop and the till. */
data class SlipHeader(val shopName: String, val shopCode: String, val counter: String)

/**
 * The receipt and Z-report layouts (doc 26 section 3.4), shared by every platform so the paper is
 * the same everywhere. Item names print in the shop's language with an "EN" tag when they fall back
 * to English.
 */
object ReceiptLayouts {

    fun receipt(r: IssuedReceipt, header: SlipHeader, language: Language, zone: TimeZone, reprint: Boolean = false): ReceiptLayout {
        val m = ReceiptMessages.of(language)
        val blocks = mutableListOf<Block>()
        if (reprint) blocks += Block.Text("*** ${m.reprint} ***", Align.CENTER, bold = true)
        blocks += Block.Text(header.shopName, Align.CENTER, TextSize.LARGE, bold = true)
        blocks += Block.Text(header.shopCode, Align.CENTER, TextSize.SMALL)
        blocks += Block.Gap(8)
        blocks += Block.Row(m.receipt, r.numberDisplay, bold = true)
        blocks += Block.Row(m.date, Times.printed(Instant.fromEpochSeconds(r.issuedAt), zone))
        blocks += Block.Row(m.cashier, r.operatorName)
        blocks += Block.Row(m.counter, header.counter)
        blocks += Block.Rule
        for (line in r.lines) {
            val tag = if (language != Language.EN && line.nameLocal == line.nameEn) " (EN)" else ""
            blocks += Block.Text(line.nameLocal + tag)
            blocks += Block.Row("  ${line.qty.display()} x ${line.unitPrice.display()}", line.lineTotal.display(), TextSize.SMALL)
        }
        blocks += Block.Rule
        blocks += Block.Row(m.total, "LKR ${r.gross.display()}", TextSize.LARGE, bold = true)
        blocks += Block.Row(m.cash, r.tendered.display())
        blocks += Block.Row(m.change, r.change.display())
        blocks += Block.Rule
        blocks += Block.Text(m.thanks, Align.CENTER, bold = true)
        blocks += Block.Gap(24)
        return ReceiptLayout(language, blocks)
    }

    fun zReport(z: ZReport, header: SlipHeader, language: Language, zone: TimeZone): ReceiptLayout {
        val m = ReceiptMessages.of(language)
        val s = z.session
        val blocks = mutableListOf<Block>()
        blocks += Block.Text(m.zReport, Align.CENTER, TextSize.LARGE, bold = true)
        blocks += Block.Text(header.shopName, Align.CENTER, bold = true)
        blocks += Block.Gap(8)
        blocks += Block.Row(m.counter, header.counter)
        blocks += Block.Row(m.cashier, s.operatorName)
        blocks += Block.Row(m.businessDate, s.businessDate.toString())
        blocks += Block.Row(m.opened, Times.printed(Instant.fromEpochSeconds(s.openedAt), zone))
        s.closedAt?.let { blocks += Block.Row(m.closed, Times.printed(Instant.fromEpochSeconds(it), zone)) }
        blocks += Block.Rule
        blocks += Block.Row(m.receipts, z.receiptCount.toString())
        if (z.firstReceipt != null) blocks += Block.Row("  ${z.firstReceipt}", "… ${z.lastReceipt}", TextSize.SMALL)
        for (item in z.items) {
            val tag = if (language != Language.EN && item.nameLocal == item.nameEn) " (EN)" else ""
            blocks += Block.Row("${item.nameLocal}$tag × ${item.qty.display()}", item.amount.display(), TextSize.SMALL)
        }
        blocks += Block.Rule
        blocks += Block.Row(m.sales, z.grossSales.display(), bold = true)
        blocks += Block.Row(m.cash, z.cashSales.display())
        blocks += Block.Row(m.float, z.floatAmount.display())
        blocks += Block.Row(m.expected, z.expectedCash.display(), bold = true)
        z.countedCash?.let { blocks += Block.Row(m.counted, it.display()) }
        z.variance?.let { blocks += Block.Row(m.variance, it.display(), bold = true) }
        blocks += Block.Gap(24)
        return ReceiptLayout(language, blocks)
    }
}
