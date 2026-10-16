package lk.coopfed.knoweb.till.core.sale

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lk.coopfed.knoweb.till.core.model.CashMovementRecord
import lk.coopfed.knoweb.till.core.model.DeviceIdentity
import lk.coopfed.knoweb.till.core.model.IssuedReceipt
import lk.coopfed.knoweb.till.core.model.OutboxEntry
import lk.coopfed.knoweb.till.core.model.ReceiptLine
import lk.coopfed.knoweb.till.core.model.SessionRecord
import lk.coopfed.knoweb.till.core.model.TenderRecord
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.time.Times

/**
 * The facts a till writes, in the shape central's ingestors read them (the backend's till simulator
 * is the reference: TillSimulator.openSession, sell, closeSession). Pure functions: the service
 * numbers them and puts them in the outbox in one transaction.
 */
object Facts {

    /** till_session.opened.v1 */
    fun sessionOpened(device: DeviceIdentity, s: SessionRecord): JsonObject = buildJsonObject {
        put("session_id", s.sessionId)
        put("till_position_id", device.tillPositionId)
        put("operator_user_id", s.operatorUserId)
        put("business_date", s.businessDate.toString())
        put("opened_at", Times.iso(Instant.fromEpochSeconds(s.openedAt)))
        put("float_amount", s.floatAmount.plain())
    }

    /** till_session.closed.v1: the blind count and the till's expected figure. */
    fun sessionClosed(device: DeviceIdentity, s: SessionRecord): JsonObject = buildJsonObject {
        put("session_id", s.sessionId)
        put("till_position_id", device.tillPositionId)
        put("operator_user_id", s.operatorUserId)
        put("business_date", s.businessDate.toString())
        put("closed_at", Times.iso(Instant.fromEpochSeconds(s.closedAt!!)))
        put("counted_cash", s.countedCash!!.plain())
        put("expected_cash", s.expectedCash!!.plain())
        put("variance", s.variance!!.plain())
    }

    /** cash_movement.recorded.v1: cash drop or pay-out at the till drawer. */
    fun cashMovement(device: DeviceIdentity, m: CashMovementRecord, occurredAt: Instant): JsonObject = buildJsonObject {
        put("movement_id", m.movementId)
        put("session_id", m.sessionId)
        put("till_position_id", device.tillPositionId)
        put("kind", m.kind)
        put("amount", m.amount.plain())
        put("occurred_at", Times.iso(occurredAt))
        put("operator_user_id", m.operatorUserId)
        put("reason", m.reason)
        m.deviceSeq?.let { put("device_seq", it) }
    }

    /** The document and lines of a receipt bundle (receipt.issued.v1), with doc 18's column names. */
    fun receiptDocument(
        device: DeviceIdentity,
        documentId: String,
        number: Long,
        display: String,
        issuedAt: Instant,
        businessDate: LocalDate,
        operatorUserId: String,
        gross: Money,
        deviceSeq: Long,
    ): JsonObject = buildJsonObject {
        put("document_id", documentId)
        put("doc_type_code", "RCT")
        put("series_id", device.receiptSeriesId)
        put("doc_number", number)
        put("doc_number_display", display)
        put("owner_entity_id", device.ownerEntityId)
        put("location_id", device.locationId)
        put("till_position_id", device.tillPositionId)
        put("device_id", device.deviceId)
        put("issued_at", Times.iso(issuedAt))
        put("business_date", businessDate.toString())
        put("operator_user_id", operatorUserId)
        put("currency", "LKR")
        put("net_amount", gross.plain())
        put("tax_amount", "0.00")
        put("gross_amount", gross.plain())
        put("origin", "OFFLINE")
        put("device_seq", deviceSeq)
    }

    fun receiptLines(lines: List<ReceiptLine>): JsonArray = buildJsonArray {
        for (line in lines) {
            add(buildJsonObject {
                put("line_no", line.lineNo)
                put("sku_id", line.skuId)
                put("uom_code", line.uom)
                put("qty", line.qty.plain())
                put("unit_price", line.unitPrice.plain())
                put("tax_amount", "0.00")
                put("line_total", line.lineTotal.plain())
            })
        }
    }

    /** The whole receipt.issued.v1 payload: document, lines, the tenders, the session and the hash. */
    fun receiptPayload(document: JsonObject, lines: JsonArray, receipt: IssuedReceipt): JsonObject = buildJsonObject {
        put("document", document)
        put("lines", lines)
        put("tenders", buildJsonArray {
            val list = receipt.tenders.ifEmpty {
                listOf(TenderRecord(seq = 1, kind = "CASH", amount = receipt.gross))
            }
            for (tender in list) {
                add(buildJsonObject {
                    put("seq", tender.seq)
                    put("kind", tender.kind)
                    put("amount", tender.amount.plain())
                    tender.reference?.let { put("reference", it) }
                    tender.customerAccountId?.let { put("customer_account_id", it) }
                    if (tender.changeGiven > Money.ZERO) {
                        put("change_given", tender.changeGiven.plain())
                    }
                    if (tender.offline) {
                        put("offline", true)
                    }
                })
            }
        })
        put("session_id", receipt.sessionId)
        put("content_hash", receipt.contentHash)
    }

    /** The audit type a till records when wrong PINs lock it (kernel seed audit-event-types.yaml, offline_capturable). */
    const val PIN_LOCKOUT_AUDIT = "TILL_PIN_LOCKOUT"
    const val PIN_LOCKOUT_EVENT = "audit.till_pin_lockout.v1"

    /**
     * The till's audit row for a PIN lock-out (26A section 8, "local + audit"; doc 32 section 3.1,
     * the audit.* family the kernel applies as a device audit row). It names the till position and
     * the lock-out's end, never a PIN or which PINs were tried.
     */
    fun pinLockout(device: DeviceIdentity, failures: Int, lockedUntil: Instant): JsonObject = buildJsonObject {
        put("event_type_code", PIN_LOCKOUT_AUDIT)
        put("subject_table", "till_position")
        put("subject_id", device.tillPositionId)
        put("till_position_id", device.tillPositionId)
        put("reason_code", "PIN_FAILURES")
        put("after_state", buildJsonObject {
            put("failures", failures)
            put("locked_until", Times.iso(lockedUntil))
        })
    }

    /** The audit type a till records when a supervisor moves its business date back (kernel seed audit-event-types.yaml, offline_capturable). */
    const val BUSINESS_DATE_CORRECTED_AUDIT = "TILL_BUSINESS_DATE_CORRECTED"
    const val BUSINESS_DATE_CORRECTED_EVENT = "audit.till_business_date_corrected.v1"

    /**
     * The till's audit row for a business date moved back (decision D-4; TILLM6-05): the till
     * position, the date before and after. Who moved it is the envelope's actor (the supervisor);
     * no PIN is carried.
     */
    fun businessDateCorrected(device: DeviceIdentity, from: LocalDate, to: LocalDate): JsonObject = buildJsonObject {
        put("event_type_code", BUSINESS_DATE_CORRECTED_AUDIT)
        put("subject_table", "till_position")
        put("subject_id", device.tillPositionId)
        put("till_position_id", device.tillPositionId)
        put("reason_code", "BUSINESS_DATE_BACK")
        put("before_state", buildJsonObject { put("business_date", from.toString()) })
        put("after_state", buildJsonObject { put("business_date", to.toString()) })
    }

    /** The audit type a till records when operators switch (kernel seed audit-event-types.yaml, offline_capturable). */
    const val OPERATOR_SWITCH_AUDIT = "TILL_OPERATOR_SWITCH"
    const val OPERATOR_SWITCH_EVENT = "audit.till_operator_switch.v1"

    /**
     * The till's audit row for an operator switch (doc 26A section 8; C-02; TILL-PLAT-02).
     */
    fun operatorSwitched(device: DeviceIdentity, fromOperatorId: String?, toOperatorId: String, reason: String? = null): JsonObject = buildJsonObject {
        put("event_type_code", OPERATOR_SWITCH_AUDIT)
        put("subject_table", "till_position")
        put("subject_id", device.tillPositionId)
        put("till_position_id", device.tillPositionId)
        put("reason_code", "OPERATOR_SWITCH")
        if (reason != null) put("reason_text", reason)
        if (fromOperatorId != null) put("before_state", buildJsonObject { put("operator_user_id", fromOperatorId) })
        put("after_state", buildJsonObject { put("operator_user_id", toOperatorId) })
    }

    /** The TillEvent envelope (sync.yaml TillEvent) around a payload. */
    fun event(
        eventId: String,
        eventType: String,
        deviceSeq: Long,
        occurredAt: Instant,
        zone: TimeZone,
        actorUserId: String?,
        payload: JsonObject,
        aggregateType: String? = null,
        aggregateId: String? = null,
        contentHash: String? = null,
        rawInstant: Instant = occurredAt,
        offlineTimed: Boolean = false,
        clockOffsetMs: Long? = null,
    ): OutboxEntry {
        val json = buildJsonObject {
            put("event_id", eventId)
            put("event_type", eventType)
            put("device_seq", deviceSeq)
            put("occurred_at", Times.iso(occurredAt))
            put("occurred_local", Times.local(rawInstant, zone))
            if (offlineTimed) put("offline_timed", true)
            if (clockOffsetMs != null) put("clock_offset_ms", clockOffsetMs)
            put("actor_user_id", actorUserId?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
            if (aggregateType != null) put("aggregate_type", aggregateType)
            if (aggregateId != null) put("aggregate_id", aggregateId)
            if (contentHash != null) put("content_hash", contentHash)
            put("payload", payload)
        }
        return OutboxEntry(deviceSeq, eventId, eventType, json.toString())
    }
}
