package lk.coopfed.knoweb.till.core.model

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.money.Qty

/**
 * Who this till is, from the enrolment answer (sync.yaml EnrolmentResponse): its device, shop and
 * till position, its credential at the identity provider, central's snapshot key, and the RCT
 * series its receipts are numbered from. Kept in the encrypted database.
 */
@Serializable
data class DeviceIdentity(
    val serverUrl: String,
    val deviceId: String,
    val hardwareSerial: String,
    val ownerEntityId: String,
    val locationId: String,
    val tillPositionId: String,
    val positionNo: Int?,
    val primaryTill: Boolean,
    val clientId: String,
    val clientSecret: String,
    val tokenEndpoint: String,
    val signingKeyId: String,
    val signingPublicKey: String,
    val receiptSeriesId: String,
    val receiptPrefix: String,
)

/** A till session (doc 26 section 3.5): one open per till position; blind close. */
@Serializable
data class SessionRecord(
    val sessionId: String,
    val operatorUserId: String,
    val operatorName: String,
    val businessDate: LocalDate,
    /** Epoch seconds (the facts carry whole seconds). */
    val openedAt: Long,
    val floatAmount: Money,
    val closedAt: Long? = null,
    val countedCash: Money? = null,
    val expectedCash: Money? = null,
    /** The device sequence of till_session.opened.v1 and .closed.v1, for the Z-report's refused facts. */
    val openedSeq: Long? = null,
    val closedSeq: Long? = null,
) {
    val isOpen: Boolean get() = closedAt == null
    val variance: Money? get() = if (countedCash != null && expectedCash != null) countedCash - expectedCash else null
}

/** A line on an issued receipt, as the till printed it. */
@Serializable
data class ReceiptLine(
    val lineNo: Int,
    val skuId: String,
    val nameEn: String,
    val nameLocal: String,
    val uom: String,
    val qty: Qty,
    val unitPrice: Money,
    val lineTotal: Money,
)

/** A tender applied to a receipt (doc 26 section 3.3). */
@Serializable
data class TenderRecord(
    val seq: Int,
    val kind: String,
    val amount: Money,
    val reference: String? = null,
    val customerAccountId: String? = null,
    val changeGiven: Money = Money.ZERO,
    val offline: Boolean = false,
)

/** A receipt the till issued: a fact, never changed afterwards (doc 26; AGENTS.md rule 3). */
@Serializable
data class IssuedReceipt(
    val documentId: String,
    val sessionId: String,
    val number: Long,
    val numberDisplay: String,
    val issuedAt: Long,
    val businessDate: LocalDate,
    val operatorUserId: String,
    val operatorName: String,
    val lines: List<ReceiptLine>,
    val gross: Money,
    val tendered: Money,
    val change: Money,
    val contentHash: String,
    val deviceSeq: Long,
    val tenders: List<TenderRecord> = emptyList(),
)

/** A fact waiting in the outbox for central (doc 32 section 3). */
data class OutboxEntry(
    val deviceSeq: Long,
    val eventId: String,
    val eventType: String,
    /** The TillEvent as JSON text, exactly as it is uploaded. */
    val json: String,
)

/**
 * Something the office must know about this till (TWK-05, TWK-06, TWK-07): a fact central refused,
 * a sequence central asked for that the till no longer holds, a counter repaired at start, a clock
 * that was set by hand. Kept locally, counted on the status bar and on the Z-report.
 */
data class Anomaly(
    val kind: String,
    val deviceSeq: Long?,
    val eventId: String?,
    val reason: String?,
    val detail: String,
    /** Epoch ms on the till's clock. */
    val notedAt: Long,
    val seen: Boolean = false,
) {
    companion object {
        /** central answered QUARANTINED for the fact with this device sequence. */
        const val QUARANTINED = "QUARANTINED"
        /** central asked for a sequence (RESEND_FROM, 409 sync.sequence_gap) the till has purged. */
        const val RESEND_IMPOSSIBLE = "RESEND_IMPOSSIBLE"
        /** A counter was at or below a number already used and was moved on at start. */
        const val COUNTER_REPAIRED = "COUNTER_REPAIRED"
        /** The PC's clock was set by hand, or central's offset was too large: the offset is not used. */
        const val CLOCK = "CLOCK"
    }
}

/**
 * A clock snapshot captured consistently at event creation time.
 */
data class ClockReading(
    /** The till's clock with active central offset applied, if any. */
    val occurredAt: Instant,
    /** The raw device wall-clock reading at capture time. */
    val rawInstant: Instant,
    /** The offset (server - device) in ms actually applied to occurredAt, or null if no offset was active. */
    val clockOffsetMs: Long?,
    /** True when recorded without an active clock sync offset. */
    val offlineTimed: Boolean,
)

/** The kind of cash movement at a till drawer (doc 26 section 3.5). */
object CashMovementKind {
    const val DROP = "DROP"
    const val PAYOUT = "PAYOUT"
}

/** A cash drop or pay-out recorded at a till (doc 26 section 3.5). */
@Serializable
data class CashMovementRecord(
    val movementId: String,
    val sessionId: String,
    val kind: String,
    val amount: Money,
    val occurredAt: Long,
    val operatorUserId: String,
    val reason: String,
    val deviceSeq: Long? = null,
)
