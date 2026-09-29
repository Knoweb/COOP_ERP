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
import lk.coopfed.knoweb.till.core.model.DeviceIdentity
import lk.coopfed.knoweb.till.core.model.IssuedReceipt
import lk.coopfed.knoweb.till.core.model.OutboxEntry
import lk.coopfed.knoweb.till.core.model.ReceiptLine
import lk.coopfed.knoweb.till.core.model.SessionRecord
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

    /** The whole receipt.issued.v1 payload: document, lines, the cash tender, the session and the hash. */
    fun receiptPayload(document: JsonObject, lines: JsonArray, receipt: IssuedReceipt): JsonObject = buildJsonObject {
        put("document", document)
        put("lines", lines)
        put("tenders", buildJsonArray {
            add(buildJsonObject {
                put("seq", 1)
                put("kind", "CASH")
                put("amount", receipt.gross.plain())
            })
        })
        put("session_id", receipt.sessionId)
        put("content_hash", receipt.contentHash)
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
    ): OutboxEntry {
        val json = buildJsonObject {
            put("event_id", eventId)
            put("event_type", eventType)
            put("device_seq", deviceSeq)
            put("occurred_at", Times.iso(occurredAt))
            put("occurred_local", Times.local(occurredAt, zone))
            put("actor_user_id", actorUserId?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
            if (aggregateType != null) put("aggregate_type", aggregateType)
            if (aggregateId != null) put("aggregate_id", aggregateId)
            if (contentHash != null) put("content_hash", contentHash)
            put("payload", payload)
        }
        return OutboxEntry(deviceSeq, eventId, eventType, json.toString())
    }
}
