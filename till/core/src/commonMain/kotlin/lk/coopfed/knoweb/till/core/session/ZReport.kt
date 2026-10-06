package lk.coopfed.knoweb.till.core.session

import lk.coopfed.knoweb.till.core.model.IssuedReceipt
import lk.coopfed.knoweb.till.core.model.SessionRecord
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.money.Qty

/** One item's total in a session. */
data class ItemTotal(val skuId: String, val nameEn: String, val nameLocal: String, val qty: Qty, val amount: Money)

/**
 * The Z-report of a closed session (doc 26 section 3.5, I-03): what was sold, the cash the drawer
 * should hold, what the cashier counted and the variance. Computed only from the session and its
 * receipts, so a reprint is always the same report.
 */
data class ZReport(
    val session: SessionRecord,
    val receiptCount: Int,
    val firstReceipt: String?,
    val lastReceipt: String?,
    val grossSales: Money,
    val cashSales: Money,
    val items: List<ItemTotal>,
    /**
     * The session's facts central refused (QUARANTINED) as far as the till knows: they are still
     * sales on paper and in the drawer, and the office must repair them at central (TWK-05).
     */
    val refusedFacts: Long = 0,
) {
    val floatAmount: Money get() = session.floatAmount
    val expectedCash: Money get() = session.expectedCash ?: SessionRules.expectedCash(session, cashSales)
    val countedCash: Money? get() = session.countedCash
    val variance: Money? get() = session.variance

    companion object {
        /** The device sequences of the session's own facts: its opening, its receipts, its close. */
        fun factsOf(session: SessionRecord, receipts: List<IssuedReceipt>): List<Long> =
            listOfNotNull(session.openedSeq, session.closedSeq) + receipts.map { it.deviceSeq }

        fun of(session: SessionRecord, receipts: List<IssuedReceipt>, refusedFacts: Long = 0): ZReport {
            val ordered = receipts.sortedBy { it.number }
            val gross = ordered.fold(Money.ZERO) { sum, r -> sum + r.gross }
            val items = ordered.flatMap { it.lines }
                .groupBy { it.skuId }
                .map { (sku, lines) ->
                    ItemTotal(
                        skuId = sku,
                        nameEn = lines.first().nameEn,
                        nameLocal = lines.first().nameLocal,
                        qty = lines.fold(Qty(0)) { q, l -> q + l.qty },
                        amount = lines.fold(Money.ZERO) { m, l -> m + l.lineTotal },
                    )
                }
                .sortedBy { it.nameEn }
            return ZReport(
                session = session,
                receiptCount = ordered.size,
                firstReceipt = ordered.firstOrNull()?.numberDisplay,
                lastReceipt = ordered.lastOrNull()?.numberDisplay,
                grossSales = gross,
                // Cash is the only tender of the trial: every receipt was paid in cash.
                cashSales = gross,
                items = items,
                refusedFacts = refusedFacts,
            )
        }
    }
}

/** The rules of a session's cash (doc 26 section 3.5). */
object SessionRules {
    /**
     * expected = float + cash tenders − change − drops. The trial records the cash tender at the
     * receipt's total (the change is already taken off) and has no drops, so expected = float + cash sales.
     */
    fun expectedCash(session: SessionRecord, cashSales: Money): Money = session.floatAmount + cashSales
}
