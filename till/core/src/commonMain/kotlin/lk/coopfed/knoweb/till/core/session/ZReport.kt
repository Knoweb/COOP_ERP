package lk.coopfed.knoweb.till.core.session

import lk.coopfed.knoweb.till.core.model.CashMovementKind
import lk.coopfed.knoweb.till.core.model.CashMovementRecord
import lk.coopfed.knoweb.till.core.model.IssuedReceipt
import lk.coopfed.knoweb.till.core.model.SessionRecord
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.money.Qty

/** One item's total in a session. */
data class ItemTotal(val skuId: String, val nameEn: String, val nameLocal: String, val qty: Qty, val amount: Money)

/**
 * The Z-report of a closed session (doc 26 section 3.5, I-03): what was sold, the cash the drawer
 * should hold, what the cashier counted and the variance. Computed only from the session, its
 * receipts, and its cash movements, so a reprint is always the same report.
 */
data class ZReport(
    val session: SessionRecord,
    val receiptCount: Int,
    val firstReceipt: String?,
    val lastReceipt: String?,
    val grossSales: Money,
    val cashSales: Money,
    val drops: Money = Money.ZERO,
    val payouts: Money = Money.ZERO,
    val items: List<ItemTotal>,
    /**
     * The session's facts central refused (QUARANTINED) as far as the till knows: they are still
     * sales on paper and in the drawer, and the office must repair them at central (TWK-05).
     */
    val refusedFacts: Long = 0,
) {
    val floatAmount: Money get() = session.floatAmount
    val expectedCash: Money get() = session.expectedCash ?: SessionRules.expectedCash(session, cashSales, drops, payouts)
    val countedCash: Money? get() = session.countedCash
    val variance: Money? get() = session.variance

    companion object {
        /** The device sequences of the session's own facts: its opening, its receipts, its cash movements, its close. */
        fun factsOf(
            session: SessionRecord,
            receipts: List<IssuedReceipt>,
            cashMovements: List<CashMovementRecord> = emptyList(),
        ): List<Long> =
            listOfNotNull(session.openedSeq, session.closedSeq) +
                receipts.map { it.deviceSeq } +
                cashMovements.mapNotNull { it.deviceSeq }

        fun of(
            session: SessionRecord,
            receipts: List<IssuedReceipt>,
            refusedFacts: Long = 0,
            cashMovements: List<CashMovementRecord> = emptyList(),
        ): ZReport {
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
            val cash = ordered.fold(Money.ZERO) { s, r ->
                if (r.tenders.isNotEmpty()) {
                    s + r.tenders.filter { it.kind.uppercase() == "CASH" }.fold(Money.ZERO) { cs, t -> cs + t.amount }
                } else {
                    s + r.gross
                }
            }
            val drops = cashMovements
                .filter { it.kind.uppercase() == CashMovementKind.DROP }
                .fold(Money.ZERO) { sum, m -> sum + m.amount }
            val payouts = cashMovements
                .filter { it.kind.uppercase() == CashMovementKind.PAYOUT }
                .fold(Money.ZERO) { sum, m -> sum + m.amount }
            return ZReport(
                session = session,
                receiptCount = ordered.size,
                firstReceipt = ordered.firstOrNull()?.numberDisplay,
                lastReceipt = ordered.lastOrNull()?.numberDisplay,
                grossSales = gross,
                cashSales = cash,
                drops = drops,
                payouts = payouts,
                items = items,
                refusedFacts = refusedFacts,
            )
        }
    }
}

/** The rules of a session's cash (doc 26 section 3.5). */
object SessionRules {
    /**
     * expected = float + cash tenders − change − drops − payouts.
     */
    fun expectedCash(
        session: SessionRecord,
        cashSales: Money,
        drops: Money = Money.ZERO,
        payouts: Money = Money.ZERO,
    ): Money = session.floatAmount + cashSales - drops - payouts
}
