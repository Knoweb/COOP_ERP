package lk.coopfed.knoweb.till.core.shop

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lk.coopfed.knoweb.till.core.id.IdGenerator
import lk.coopfed.knoweb.till.core.id.UuidV7
import lk.coopfed.knoweb.till.core.model.ClockReading
import lk.coopfed.knoweb.till.core.model.DeviceIdentity
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.money.Qty
import lk.coopfed.knoweb.till.core.sale.ContentHash
import lk.coopfed.knoweb.till.core.sale.Facts
import lk.coopfed.knoweb.till.core.time.Times

/**
 * Pure builders for the five offline shop-operation bundles (doc 32 §3.1, doc 24A §7.4, doc 25A §7.4):
 * - Goods Received Note (GRN): `grn.confirmed.v1`
 * - Stock Count: `count.recorded.v1`
 * - Write-off: `writeoff.requested.v1`
 * - Repack: `repack.executed.v1`
 * - Stock Transfer: `transfer.issued.v1`
 *
 * Each builder computes the canonical SHA-256 [ContentHash] over the document header and lines,
 * generates the atomic outbox entry conforming to `BundleShape`, and produces all matching
 * document lines, state history, stock movement, and audit records.
 */
object ShopBundleBuilder {

    private val defaultIds: IdGenerator = UuidV7({ kotlin.time.Clock.System.now().toEpochMilliseconds() })

    // =========================================================================
    // 1. Goods Received Note (GRN) — grn.confirmed.v1
    // =========================================================================

    fun buildGrnBundle(
        device: DeviceIdentity,
        operatorUserId: String,
        businessDate: LocalDate,
        clockReading: ClockReading,
        zone: TimeZone,
        input: GrnOperationInput,
        docNumber: Long,
        deviceSeq: Long,
        seriesId: String = device.receiptSeriesId,
        seriesPrefix: String = device.receiptPrefix.replace("-RCT", "-GRN").ifEmpty { "GRN" },
        documentId: String = defaultIds.next(),
        eventId: String = defaultIds.next(),
        ids: IdGenerator = defaultIds,
    ): ShopOperationBundle {
        require(input.lines.isNotEmpty()) { "GRN must have at least one line" }
        val now = clockReading.occurredAt
        val issuedEpochSeconds = Times.wholeSecond(now).epochSeconds
        val issuedLocal = Times.local(clockReading.rawInstant, zone)
        val display = "$seriesPrefix-$docNumber"

        var totalNet = Money.ZERO
        val lines = input.lines.mapIndexed { idx, line ->
            require(line.receivedQty >= Qty.ZERO) { "Received quantity cannot be negative" }
            require(line.damagedQty >= Qty.ZERO) { "Damaged quantity cannot be negative" }
            val lineNo = idx + 1
            val lineTotal = line.unitCost * line.receivedQty
            totalNet += lineTotal
            ShopDocumentLine(
                lineNo = lineNo,
                lineId = ids.next(),
                skuId = line.skuId,
                batchId = line.batchId,
                batchNo = line.batchNo,
                uomCode = line.uomCode,
                qty = line.receivedQty,
                unitPrice = line.unitCost,
                lineTotal = lineTotal,
                unitCostAtIssue = line.unitCost,
                receivedQty = line.receivedQty,
                damagedQty = line.damagedQty,
                expectedQty = line.expectedQty,
                condition = if (line.damagedQty > Qty.ZERO && line.receivedQty == Qty.ZERO) "DAMAGED" else "GOOD",
            )
        }

        val header = ShopDocumentHeader(
            documentId = documentId,
            docTypeCode = "GRN",
            seriesId = seriesId,
            docNumber = docNumber,
            docNumberDisplay = display,
            ownerEntityId = device.ownerEntityId,
            counterpartyEntityId = input.supplierId,
            locationId = device.locationId,
            tillPositionId = device.tillPositionId,
            deviceId = device.deviceId,
            status = "CONFIRMED",
            issuedAt = issuedEpochSeconds,
            issuedLocal = issuedLocal,
            businessDate = businessDate,
            operatorUserId = operatorUserId,
            currency = "LKR",
            netAmount = totalNet,
            taxAmount = Money.ZERO,
            grossAmount = totalNet,
            origin = "OFFLINE",
            deviceSeq = deviceSeq,
        )

        val docJson = buildDocumentJson(header, now)
        val linesJson = buildLinesJson(lines)
        val hash = ContentHash.of(docJson, linesJson)

        val payload = buildJsonObject {
            put("document", docJson)
            put("lines", linesJson)
            put("grn", buildJsonObject {
                input.dropId?.let { put("drop_id", it) }
                input.supplierId?.let { put("supplier_id", it) }
                input.deliveryDocumentId?.let { put("delivery_document_id", it) }
                put("received_on", businessDate.toString())
                put("counted_by", input.countedBy ?: operatorUserId)
                put("confirmed_by", input.confirmedBy ?: operatorUserId)
                put("confirmed_at", Times.iso(now))
            })
            put("state_history", buildStateHistoryJson("DRAFT", "CONFIRMED", now, operatorUserId))
            if (input.attachments.isNotEmpty()) {
                put("attachments", buildAttachmentsJson(input.attachments))
            }
            put("content_hash", hash)
        }

        val eventType = "grn.confirmed.v1"
        val outbox = Facts.event(
            eventId = eventId,
            eventType = eventType,
            deviceSeq = deviceSeq,
            occurredAt = clockReading.occurredAt,
            zone = zone,
            actorUserId = operatorUserId,
            payload = payload,
            aggregateType = "grn",
            aggregateId = documentId,
            contentHash = hash,
            rawInstant = clockReading.rawInstant,
            offlineTimed = clockReading.offlineTimed,
            clockOffsetMs = clockReading.clockOffsetMs,
        )

        val stateHistory = listOf(
            ShopStateHistory(
                historyId = ids.next(),
                documentId = documentId,
                fromStatus = "DRAFT",
                toStatus = "CONFIRMED",
                occurredAt = issuedEpochSeconds,
                occurredLocal = issuedLocal,
                actorUserId = operatorUserId,
            ),
        )

        val stockMovements = mutableListOf<ShopStockMovement>()
        lines.forEach { l ->
            val goodQty = l.receivedQty?.let { it - (l.damagedQty ?: Qty.ZERO) } ?: l.qty
            if (goodQty > Qty.ZERO) {
                stockMovements += ShopStockMovement(
                    movementId = ids.next(),
                    movementSeq = null,
                    locationId = device.locationId,
                    skuId = l.skuId,
                    batchId = l.batchId,
                    condition = "GOOD",
                    qtyDelta = goodQty.plain(),
                    movementType = "RECEIPT",
                    unitCostAtMovement = l.unitCostAtIssue,
                    documentId = documentId,
                    documentLineId = l.lineId,
                    occurredAt = issuedEpochSeconds,
                    occurredLocal = issuedLocal,
                    operatorUserId = operatorUserId,
                    deviceId = device.deviceId,
                )
            }
            if (l.damagedQty != null && l.damagedQty > Qty.ZERO) {
                stockMovements += ShopStockMovement(
                    movementId = ids.next(),
                    movementSeq = null,
                    locationId = device.locationId,
                    skuId = l.skuId,
                    batchId = l.batchId,
                    condition = "DAMAGED",
                    qtyDelta = l.damagedQty.plain(),
                    movementType = "RECEIPT",
                    unitCostAtMovement = l.unitCostAtIssue,
                    documentId = documentId,
                    documentLineId = l.lineId,
                    occurredAt = issuedEpochSeconds,
                    occurredLocal = issuedLocal,
                    operatorUserId = operatorUserId,
                    deviceId = device.deviceId,
                )
            }
        }

        val auditEvents = listOf(
            ShopAuditEvent(
                auditId = ids.next(),
                eventTypeCode = "GRN_CONFIRMED",
                occurredAt = issuedEpochSeconds,
                occurredLocal = issuedLocal,
                ownerEntityId = device.ownerEntityId,
                locationId = device.locationId,
                actorUserId = operatorUserId,
                deviceId = device.deviceId,
                tillPositionId = device.tillPositionId,
                subjectTable = "document",
                subjectId = documentId,
                documentId = documentId,
                deviceSeq = deviceSeq,
            ),
        )

        return ShopOperationBundle(
            document = header,
            lines = lines,
            eventType = eventType,
            contentHash = hash,
            outboxEntry = outbox,
            stateHistory = stateHistory,
            stockMovements = stockMovements,
            auditEvents = auditEvents,
            attachments = input.attachments,
        )
    }

    // =========================================================================
    // 2. Stock Count — count.recorded.v1
    // =========================================================================

    fun buildStockCountBundle(
        device: DeviceIdentity,
        operatorUserId: String,
        businessDate: LocalDate,
        clockReading: ClockReading,
        zone: TimeZone,
        input: StockCountOperationInput,
        docNumber: Long,
        deviceSeq: Long,
        seriesId: String = device.receiptSeriesId,
        seriesPrefix: String = device.receiptPrefix.replace("-RCT", "-CNT").ifEmpty { "CNT" },
        documentId: String = defaultIds.next(),
        eventId: String = defaultIds.next(),
        ids: IdGenerator = defaultIds,
    ): ShopOperationBundle {
        require(input.lines.isNotEmpty()) { "Stock count must have at least one line" }
        val now = clockReading.occurredAt
        val issuedEpochSeconds = Times.wholeSecond(now).epochSeconds
        val issuedLocal = Times.local(clockReading.rawInstant, zone)
        val display = "$seriesPrefix-$docNumber"

        val lines = input.lines.mapIndexed { idx, line ->
            require(line.countedQty >= Qty.ZERO) { "Counted quantity cannot be negative" }
            val lineNo = idx + 1
            ShopDocumentLine(
                lineNo = lineNo,
                lineId = ids.next(),
                skuId = line.skuId,
                batchId = line.batchId,
                uomCode = line.uomCode,
                qty = line.countedQty,
                unitPrice = Money.ZERO,
                lineTotal = Money.ZERO,
                unitCostAtIssue = Money.ZERO,
                expectedQty = line.expectedQty,
                countedQty = line.countedQty,
                condition = line.condition,
                skipped = line.skipped,
                skipReason = line.skipReason,
            )
        }

        val header = ShopDocumentHeader(
            documentId = documentId,
            docTypeCode = "CNT",
            seriesId = seriesId,
            docNumber = docNumber,
            docNumberDisplay = display,
            ownerEntityId = device.ownerEntityId,
            counterpartyEntityId = null,
            locationId = device.locationId,
            tillPositionId = device.tillPositionId,
            deviceId = device.deviceId,
            status = "RECORDED",
            issuedAt = issuedEpochSeconds,
            issuedLocal = issuedLocal,
            businessDate = businessDate,
            operatorUserId = operatorUserId,
            currency = "LKR",
            netAmount = Money.ZERO,
            taxAmount = Money.ZERO,
            grossAmount = Money.ZERO,
            origin = "OFFLINE",
            deviceSeq = deviceSeq,
        )

        val docJson = buildDocumentJson(header, now)
        val linesJson = buildLinesJson(lines)
        val hash = ContentHash.of(docJson, linesJson)

        val payload = buildJsonObject {
            put("document", docJson)
            put("lines", linesJson)
            put("count", buildJsonObject {
                input.taskId?.let { put("task_id", it) }
                input.snapshotVersion?.let { put("snapshot_version", it) }
            })
            put("state_history", buildStateHistoryJson("DRAFT", "RECORDED", now, operatorUserId))
            put("content_hash", hash)
        }

        val eventType = "count.recorded.v1"
        val outbox = Facts.event(
            eventId = eventId,
            eventType = eventType,
            deviceSeq = deviceSeq,
            occurredAt = clockReading.occurredAt,
            zone = zone,
            actorUserId = operatorUserId,
            payload = payload,
            aggregateType = "count",
            aggregateId = documentId,
            contentHash = hash,
            rawInstant = clockReading.rawInstant,
            offlineTimed = clockReading.offlineTimed,
            clockOffsetMs = clockReading.clockOffsetMs,
        )

        val stateHistory = listOf(
            ShopStateHistory(
                historyId = ids.next(),
                documentId = documentId,
                fromStatus = "DRAFT",
                toStatus = "RECORDED",
                occurredAt = issuedEpochSeconds,
                occurredLocal = issuedLocal,
                actorUserId = operatorUserId,
            ),
        )

        val auditEvents = listOf(
            ShopAuditEvent(
                auditId = ids.next(),
                eventTypeCode = "STOCK_COUNT_RECORDED",
                occurredAt = issuedEpochSeconds,
                occurredLocal = issuedLocal,
                ownerEntityId = device.ownerEntityId,
                locationId = device.locationId,
                actorUserId = operatorUserId,
                deviceId = device.deviceId,
                tillPositionId = device.tillPositionId,
                subjectTable = "document",
                subjectId = documentId,
                documentId = documentId,
                deviceSeq = deviceSeq,
            ),
        )

        return ShopOperationBundle(
            document = header,
            lines = lines,
            eventType = eventType,
            contentHash = hash,
            outboxEntry = outbox,
            stateHistory = stateHistory,
            stockMovements = emptyList(),
            auditEvents = auditEvents,
        )
    }

    // =========================================================================
    // 3. Write-off — writeoff.requested.v1
    // =========================================================================

    fun buildWriteOffBundle(
        device: DeviceIdentity,
        operatorUserId: String,
        businessDate: LocalDate,
        clockReading: ClockReading,
        zone: TimeZone,
        input: WriteOffOperationInput,
        docNumber: Long,
        deviceSeq: Long,
        seriesId: String = device.receiptSeriesId,
        seriesPrefix: String = device.receiptPrefix.replace("-RCT", "-WOF").ifEmpty { "WOF" },
        documentId: String = defaultIds.next(),
        eventId: String = defaultIds.next(),
        ids: IdGenerator = defaultIds,
    ): ShopOperationBundle {
        require(input.lines.isNotEmpty()) { "Write-off must have at least one line" }
        require(input.category.isNotBlank()) { "Write-off category is required" }
        val now = clockReading.occurredAt
        val issuedEpochSeconds = Times.wholeSecond(now).epochSeconds
        val issuedLocal = Times.local(clockReading.rawInstant, zone)
        val display = "$seriesPrefix-$docNumber"

        var totalNet = Money.ZERO
        val lines = input.lines.mapIndexed { idx, line ->
            require(line.qty > Qty.ZERO) { "Write-off quantity must be positive" }
            val lineNo = idx + 1
            val lineTotal = line.unitCost * line.qty
            totalNet += lineTotal
            ShopDocumentLine(
                lineNo = lineNo,
                lineId = ids.next(),
                skuId = line.skuId,
                batchId = line.batchId,
                uomCode = line.uomCode,
                qty = line.qty,
                unitPrice = line.unitCost,
                lineTotal = lineTotal,
                unitCostAtIssue = line.unitCost,
                lossCategory = line.lossCategory ?: input.category,
            )
        }

        val header = ShopDocumentHeader(
            documentId = documentId,
            docTypeCode = "WOF",
            seriesId = seriesId,
            docNumber = docNumber,
            docNumberDisplay = display,
            ownerEntityId = device.ownerEntityId,
            counterpartyEntityId = null,
            locationId = device.locationId,
            tillPositionId = device.tillPositionId,
            deviceId = device.deviceId,
            status = "REQUESTED",
            issuedAt = issuedEpochSeconds,
            issuedLocal = issuedLocal,
            businessDate = businessDate,
            operatorUserId = operatorUserId,
            currency = "LKR",
            netAmount = totalNet,
            taxAmount = Money.ZERO,
            grossAmount = totalNet,
            origin = "OFFLINE",
            deviceSeq = deviceSeq,
        )

        val docJson = buildDocumentJson(header, now)
        val linesJson = buildLinesJson(lines)
        val hash = ContentHash.of(docJson, linesJson)

        val payload = buildJsonObject {
            put("document", docJson)
            put("lines", linesJson)
            put("writeoff", buildJsonObject {
                put("category", input.category)
                put("reason", input.reason)
                input.witnessUserId?.let { put("witness_user_id", it) }
            })
            put("state_history", buildStateHistoryJson("DRAFT", "REQUESTED", now, operatorUserId, reasonCode = input.category))
            if (input.attachments.isNotEmpty()) {
                put("attachments", buildAttachmentsJson(input.attachments))
            }
            put("content_hash", hash)
        }

        val eventType = "writeoff.requested.v1"
        val outbox = Facts.event(
            eventId = eventId,
            eventType = eventType,
            deviceSeq = deviceSeq,
            occurredAt = clockReading.occurredAt,
            zone = zone,
            actorUserId = operatorUserId,
            payload = payload,
            aggregateType = "writeoff",
            aggregateId = documentId,
            contentHash = hash,
            rawInstant = clockReading.rawInstant,
            offlineTimed = clockReading.offlineTimed,
            clockOffsetMs = clockReading.clockOffsetMs,
        )

        val stateHistory = listOf(
            ShopStateHistory(
                historyId = ids.next(),
                documentId = documentId,
                fromStatus = "DRAFT",
                toStatus = "REQUESTED",
                occurredAt = issuedEpochSeconds,
                occurredLocal = issuedLocal,
                actorUserId = operatorUserId,
                reasonCode = input.category,
                reasonText = input.reason,
            ),
        )

        val stockMovements = lines.map { l ->
            ShopStockMovement(
                movementId = ids.next(),
                movementSeq = null,
                locationId = device.locationId,
                skuId = l.skuId,
                batchId = l.batchId,
                condition = l.lossCategory ?: input.category,
                qtyDelta = (-l.qty).plain(),
                movementType = "WRITE_OFF",
                unitCostAtMovement = l.unitCostAtIssue,
                documentId = documentId,
                documentLineId = l.lineId,
                occurredAt = issuedEpochSeconds,
                occurredLocal = issuedLocal,
                operatorUserId = operatorUserId,
                deviceId = device.deviceId,
            )
        }

        val auditEvents = listOf(
            ShopAuditEvent(
                auditId = ids.next(),
                eventTypeCode = "WRITEOFF_REQUESTED",
                occurredAt = issuedEpochSeconds,
                occurredLocal = issuedLocal,
                ownerEntityId = device.ownerEntityId,
                locationId = device.locationId,
                actorUserId = operatorUserId,
                deviceId = device.deviceId,
                tillPositionId = device.tillPositionId,
                subjectTable = "document",
                subjectId = documentId,
                documentId = documentId,
                reasonCode = input.category,
                deviceSeq = deviceSeq,
            ),
        )

        return ShopOperationBundle(
            document = header,
            lines = lines,
            eventType = eventType,
            contentHash = hash,
            outboxEntry = outbox,
            stateHistory = stateHistory,
            stockMovements = stockMovements,
            auditEvents = auditEvents,
            attachments = input.attachments,
        )
    }

    // =========================================================================
    // 4. Repack — repack.executed.v1
    // =========================================================================

    fun buildRepackBundle(
        device: DeviceIdentity,
        operatorUserId: String,
        businessDate: LocalDate,
        clockReading: ClockReading,
        zone: TimeZone,
        input: RepackOperationInput,
        docNumber: Long,
        deviceSeq: Long,
        seriesId: String = device.receiptSeriesId,
        seriesPrefix: String = device.receiptPrefix.replace("-RCT", "-RPK").ifEmpty { "RPK" },
        documentId: String = defaultIds.next(),
        eventId: String = defaultIds.next(),
        ids: IdGenerator = defaultIds,
    ): ShopOperationBundle {
        require(input.inputLotIds.isNotEmpty()) { "Repack must specify input lot IDs" }
        require(input.actualOutputQty > Qty.ZERO) { "Actual output quantity must be positive" }
        val now = clockReading.occurredAt
        val issuedEpochSeconds = Times.wholeSecond(now).epochSeconds
        val issuedLocal = Times.local(clockReading.rawInstant, zone)
        val display = "$seriesPrefix-$docNumber"

        val lines = listOf(
            ShopDocumentLine(
                lineNo = 1,
                lineId = ids.next(),
                skuId = input.outputSkuId,
                batchId = input.outputBatchId,
                uomCode = input.outputUom,
                qty = input.actualOutputQty,
                unitPrice = Money.ZERO,
                lineTotal = Money.ZERO,
                unitCostAtIssue = Money.ZERO,
            ),
        )

        val header = ShopDocumentHeader(
            documentId = documentId,
            docTypeCode = "RPK",
            seriesId = seriesId,
            docNumber = docNumber,
            docNumberDisplay = display,
            ownerEntityId = device.ownerEntityId,
            counterpartyEntityId = null,
            locationId = device.locationId,
            tillPositionId = device.tillPositionId,
            deviceId = device.deviceId,
            status = "EXECUTED",
            issuedAt = issuedEpochSeconds,
            issuedLocal = issuedLocal,
            businessDate = businessDate,
            operatorUserId = operatorUserId,
            currency = "LKR",
            netAmount = Money.ZERO,
            taxAmount = Money.ZERO,
            grossAmount = Money.ZERO,
            origin = "OFFLINE",
            deviceSeq = deviceSeq,
        )

        val docJson = buildDocumentJson(header, now)
        val linesJson = buildLinesJson(lines)
        val hash = ContentHash.of(docJson, linesJson)

        val payload = buildJsonObject {
            put("document", docJson)
            put("lines", linesJson)
            put("repack", buildJsonObject {
                put("recipe_id", input.recipeId)
                put("input_lot_ids", buildJsonArray {
                    input.inputLotIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                })
                put("actual_output_qty", input.actualOutputQty.plain())
            })
            put("state_history", buildStateHistoryJson("DRAFT", "EXECUTED", now, operatorUserId))
            put("content_hash", hash)
        }

        val eventType = "repack.executed.v1"
        val outbox = Facts.event(
            eventId = eventId,
            eventType = eventType,
            deviceSeq = deviceSeq,
            occurredAt = clockReading.occurredAt,
            zone = zone,
            actorUserId = operatorUserId,
            payload = payload,
            aggregateType = "repack",
            aggregateId = documentId,
            contentHash = hash,
            rawInstant = clockReading.rawInstant,
            offlineTimed = clockReading.offlineTimed,
            clockOffsetMs = clockReading.clockOffsetMs,
        )

        val stateHistory = listOf(
            ShopStateHistory(
                historyId = ids.next(),
                documentId = documentId,
                fromStatus = "DRAFT",
                toStatus = "EXECUTED",
                occurredAt = issuedEpochSeconds,
                occurredLocal = issuedLocal,
                actorUserId = operatorUserId,
            ),
        )

        val stockMovements = mutableListOf<ShopStockMovement>()
        input.consumedLots.forEach { c ->
            stockMovements += ShopStockMovement(
                movementId = ids.next(),
                movementSeq = null,
                locationId = device.locationId,
                skuId = c.skuId,
                batchId = c.batchId,
                lotId = c.lotId,
                condition = "GOOD",
                qtyDelta = (-c.consumedQty).plain(),
                movementType = "REPACK_CONSUME",
                unitCostAtMovement = c.unitCost,
                documentId = documentId,
                documentLineId = null,
                occurredAt = issuedEpochSeconds,
                occurredLocal = issuedLocal,
                operatorUserId = operatorUserId,
                deviceId = device.deviceId,
            )
        }
        stockMovements += ShopStockMovement(
            movementId = ids.next(),
            movementSeq = null,
            locationId = device.locationId,
            skuId = input.outputSkuId,
            batchId = input.outputBatchId,
            condition = "GOOD",
            qtyDelta = input.actualOutputQty.plain(),
            movementType = "REPACK_PRODUCE",
            unitCostAtMovement = Money.ZERO,
            documentId = documentId,
            documentLineId = lines[0].lineId,
            occurredAt = issuedEpochSeconds,
            occurredLocal = issuedLocal,
            operatorUserId = operatorUserId,
            deviceId = device.deviceId,
        )

        val auditEvents = listOf(
            ShopAuditEvent(
                auditId = ids.next(),
                eventTypeCode = "REPACK_EXECUTED",
                occurredAt = issuedEpochSeconds,
                occurredLocal = issuedLocal,
                ownerEntityId = device.ownerEntityId,
                locationId = device.locationId,
                actorUserId = operatorUserId,
                deviceId = device.deviceId,
                tillPositionId = device.tillPositionId,
                subjectTable = "document",
                subjectId = documentId,
                documentId = documentId,
                deviceSeq = deviceSeq,
            ),
        )

        return ShopOperationBundle(
            document = header,
            lines = lines,
            eventType = eventType,
            contentHash = hash,
            outboxEntry = outbox,
            stateHistory = stateHistory,
            stockMovements = stockMovements,
            auditEvents = auditEvents,
        )
    }

    // =========================================================================
    // 5. Stock Transfer — transfer.issued.v1
    // =========================================================================

    fun buildTransferBundle(
        device: DeviceIdentity,
        operatorUserId: String,
        businessDate: LocalDate,
        clockReading: ClockReading,
        zone: TimeZone,
        input: StockTransferOperationInput,
        docNumber: Long,
        deviceSeq: Long,
        seriesId: String = device.receiptSeriesId,
        seriesPrefix: String = device.receiptPrefix.replace("-RCT", "-XFR").ifEmpty { "XFR" },
        documentId: String = defaultIds.next(),
        eventId: String = defaultIds.next(),
        ids: IdGenerator = defaultIds,
    ): ShopOperationBundle {
        require(input.lines.isNotEmpty()) { "Transfer must have at least one line" }
        require(input.destinationLocationId.isNotBlank()) { "Destination location ID is required" }
        require(input.destinationLocationId != device.locationId) { "Destination cannot be same as source location" }
        val now = clockReading.occurredAt
        val issuedEpochSeconds = Times.wholeSecond(now).epochSeconds
        val issuedLocal = Times.local(clockReading.rawInstant, zone)
        val display = "$seriesPrefix-$docNumber"

        var totalNet = Money.ZERO
        val lines = input.lines.mapIndexed { idx, line ->
            require(line.qty > Qty.ZERO) { "Transfer quantity must be positive" }
            val lineNo = idx + 1
            val lineTotal = line.unitCost * line.qty
            totalNet += lineTotal
            ShopDocumentLine(
                lineNo = lineNo,
                lineId = ids.next(),
                skuId = line.skuId,
                batchId = line.batchId,
                uomCode = line.uomCode,
                qty = line.qty,
                unitPrice = line.unitCost,
                lineTotal = lineTotal,
                unitCostAtIssue = line.unitCost,
            )
        }

        val header = ShopDocumentHeader(
            documentId = documentId,
            docTypeCode = "XFR",
            seriesId = seriesId,
            docNumber = docNumber,
            docNumberDisplay = display,
            ownerEntityId = device.ownerEntityId,
            counterpartyEntityId = null,
            locationId = device.locationId,
            tillPositionId = device.tillPositionId,
            deviceId = device.deviceId,
            status = "ISSUED",
            issuedAt = issuedEpochSeconds,
            issuedLocal = issuedLocal,
            businessDate = businessDate,
            operatorUserId = operatorUserId,
            currency = "LKR",
            netAmount = totalNet,
            taxAmount = Money.ZERO,
            grossAmount = totalNet,
            origin = "OFFLINE",
            deviceSeq = deviceSeq,
        )

        val docJson = buildDocumentJson(header, now)
        val linesJson = buildLinesJson(lines)
        val hash = ContentHash.of(docJson, linesJson)

        val payload = buildJsonObject {
            put("document", docJson)
            put("lines", linesJson)
            put("transfer", buildJsonObject {
                put("destination_location_id", input.destinationLocationId)
                input.transferRequestId?.let { put("transfer_request_id", it) }
                input.vehicleNo?.let { put("vehicle_no", it) }
            })
            put("state_history", buildStateHistoryJson("DRAFT", "ISSUED", now, operatorUserId))
            put("content_hash", hash)
        }

        val eventType = "transfer.issued.v1"
        val outbox = Facts.event(
            eventId = eventId,
            eventType = eventType,
            deviceSeq = deviceSeq,
            occurredAt = clockReading.occurredAt,
            zone = zone,
            actorUserId = operatorUserId,
            payload = payload,
            aggregateType = "transfer",
            aggregateId = documentId,
            contentHash = hash,
            rawInstant = clockReading.rawInstant,
            offlineTimed = clockReading.offlineTimed,
            clockOffsetMs = clockReading.clockOffsetMs,
        )

        val stateHistory = listOf(
            ShopStateHistory(
                historyId = ids.next(),
                documentId = documentId,
                fromStatus = "DRAFT",
                toStatus = "ISSUED",
                occurredAt = issuedEpochSeconds,
                occurredLocal = issuedLocal,
                actorUserId = operatorUserId,
            ),
        )

        val stockMovements = lines.map { l ->
            ShopStockMovement(
                movementId = ids.next(),
                movementSeq = null,
                locationId = device.locationId,
                skuId = l.skuId,
                batchId = l.batchId,
                condition = "GOOD",
                qtyDelta = (-l.qty).plain(),
                movementType = "TRANSFER_OUT",
                unitCostAtMovement = l.unitCostAtIssue,
                documentId = documentId,
                documentLineId = l.lineId,
                occurredAt = issuedEpochSeconds,
                occurredLocal = issuedLocal,
                operatorUserId = operatorUserId,
                deviceId = device.deviceId,
            )
        }

        val auditEvents = listOf(
            ShopAuditEvent(
                auditId = ids.next(),
                eventTypeCode = "TRANSFER_ISSUED",
                occurredAt = issuedEpochSeconds,
                occurredLocal = issuedLocal,
                ownerEntityId = device.ownerEntityId,
                locationId = device.locationId,
                actorUserId = operatorUserId,
                deviceId = device.deviceId,
                tillPositionId = device.tillPositionId,
                subjectTable = "document",
                subjectId = documentId,
                documentId = documentId,
                deviceSeq = deviceSeq,
            ),
        )

        return ShopOperationBundle(
            document = header,
            lines = lines,
            eventType = eventType,
            contentHash = hash,
            outboxEntry = outbox,
            stateHistory = stateHistory,
            stockMovements = stockMovements,
            auditEvents = auditEvents,
        )
    }

    // =========================================================================
    // JSON Construction Helpers (strictly matching BundleShape & ContentHash)
    // =========================================================================

    private fun buildDocumentJson(header: ShopDocumentHeader, now: Instant): JsonObject = buildJsonObject {
        put("document_id", header.documentId)
        put("doc_type_code", header.docTypeCode)
        put("series_id", header.seriesId)
        put("doc_number", header.docNumber)
        put("doc_number_display", header.docNumberDisplay)
        put("owner_entity_id", header.ownerEntityId)
        header.counterpartyEntityId?.let { put("counterparty_entity_id", it) }
        put("location_id", header.locationId)
        put("till_position_id", header.tillPositionId)
        put("device_id", header.deviceId)
        put("issued_at", Times.iso(now))
        put("business_date", header.businessDate.toString())
        put("operator_user_id", header.operatorUserId)
        put("currency", header.currency)
        put("net_amount", header.netAmount.plain())
        put("tax_amount", header.taxAmount.plain())
        put("gross_amount", header.grossAmount.plain())
        header.referenceDocumentId?.let { put("reference_document_id", it) }
        put("origin", header.origin)
        put("device_seq", header.deviceSeq)
    }

    private fun buildLinesJson(lines: List<ShopDocumentLine>): JsonArray = buildJsonArray {
        for (line in lines) {
            add(buildJsonObject {
                put("line_no", line.lineNo)
                put("sku_id", line.skuId)
                line.batchId?.let { put("batch_id", it) }
                put("uom_code", line.uomCode)
                put("qty", line.qty.plain())
                put("unit_price", line.unitPrice.plain())
                put("tax_amount", "0.00")
                put("line_total", line.lineTotal.plain())
                line.unitCostAtIssue?.let { put("unit_cost_at_issue", it.plain()) }
                line.lossCategory?.let { put("loss_category", it) }
                line.referenceLineId?.let { put("reference_line_id", it) }
            })
        }
    }

    private fun buildStateHistoryJson(
        from: String?,
        to: String,
        occurredAt: Instant,
        actorUserId: String,
        reasonCode: String? = null,
    ): JsonArray = buildJsonArray {
        add(buildJsonObject {
            from?.let { put("from_status", it) }
            put("to_status", to)
            put("occurred_at", Times.iso(occurredAt))
            put("actor_user_id", actorUserId)
            reasonCode?.let { put("reason_code", it) }
        })
    }

    private fun buildAttachmentsJson(attachments: List<ShopAttachmentReference>): JsonArray = buildJsonArray {
        for (att in attachments) {
            add(buildJsonObject {
                put("attachment_id", att.attachmentId)
                put("object_key", att.objectKey)
                put("content_hash", att.contentHash)
            })
        }
    }
}
