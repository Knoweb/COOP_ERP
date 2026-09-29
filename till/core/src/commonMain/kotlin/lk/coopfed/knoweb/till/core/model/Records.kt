package lk.coopfed.knoweb.till.core.model

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
)

/** A fact waiting in the outbox for central (doc 32 section 3). */
data class OutboxEntry(
    val deviceSeq: Long,
    val eventId: String,
    val eventType: String,
    /** The TillEvent as JSON text, exactly as it is uploaded. */
    val json: String,
)

