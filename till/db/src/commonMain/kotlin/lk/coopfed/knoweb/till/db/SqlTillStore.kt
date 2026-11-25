package lk.coopfed.knoweb.till.db

import app.cash.sqldelight.db.SqlDriver
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import lk.coopfed.knoweb.till.core.model.Anomaly
import lk.coopfed.knoweb.till.core.model.CashMovementRecord
import lk.coopfed.knoweb.till.core.model.IssuedReceipt
import lk.coopfed.knoweb.till.core.model.OutboxEntry
import lk.coopfed.knoweb.till.core.model.ParkedSaleLine
import lk.coopfed.knoweb.till.core.model.ParkedSaleRecord
import lk.coopfed.knoweb.till.core.model.SessionRecord
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.money.Qty
import lk.coopfed.knoweb.till.core.port.TillStore
import lk.coopfed.knoweb.till.core.shop.ShopAuditEvent
import lk.coopfed.knoweb.till.core.shop.ShopDocumentLine
import lk.coopfed.knoweb.till.core.shop.ShopOperationBundle
import lk.coopfed.knoweb.till.core.shop.ShopStateHistory
import lk.coopfed.knoweb.till.core.shop.ShopStockMovement
import lk.coopfed.knoweb.till.core.snapshot.SnapshotRow
import lk.coopfed.knoweb.till.core.snapshot.SnapshotState

/** The [TillStore] port on the SQLDelight database; the same code on every platform, only the driver differs. */
class SqlTillStore(driver: SqlDriver) : TillStore {

    val db: TillDatabase = TillDatabase(driver)
    val tillQueries: TillQueries = db.tillQueries
    private val q = tillQueries
    private val json = Json { ignoreUnknownKeys = true }

    override fun <T> transaction(block: () -> T): T = db.transactionWithResult { block() }

    override fun setting(key: String): String? = q.selectSetting(key).executeAsOneOrNull()

    override fun putSetting(key: String, value: String) {
        q.upsertSetting(key, value)
    }

    override fun removeSetting(key: String) {
        q.deleteSetting(key)
    }

    override fun loadSnapshot(): SnapshotState {
        val live = mutableMapOf<String, MutableMap<String, SnapshotRow>>()
        val held = mutableListOf<SnapshotRow>()
        for (r in q.selectSnapshotRows().executeAsList()) {
            val row = SnapshotRow(r.tbl, r.row_id, r.apply_from?.let { LocalDate.parse(it) }, r.data_?.let { json.parseToJsonElement(it).jsonObject })
            if (r.held == 1L) held += row else live.getOrPut(r.tbl) { mutableMapOf() }[r.row_id] = row
        }
        val version = setting(SNAPSHOT_VERSION)?.toLongOrNull() ?: 0
        return SnapshotState(version, live, held)
    }

    override fun saveSnapshot(state: SnapshotState): Unit = transaction {
        q.deleteSnapshotRows()
        for (rows in state.live.values) {
            for (row in rows.values) q.insertSnapshotRow(row.table, row.rowId, row.applyFrom?.toString(), row.data?.toString(), 0)
        }
        for (row in state.held) q.insertSnapshotRow(row.table, row.rowId, row.applyFrom?.toString(), row.data?.toString(), 1)
        putSetting(SNAPSHOT_VERSION, state.version.toString())
    }

    override fun appendOutbox(entry: OutboxEntry) {
        q.insertOutbox(entry.deviceSeq, entry.eventId, entry.eventType, entry.json)
    }

    override fun pendingOutbox(limit: Int): List<OutboxEntry> =
        q.selectPendingOutbox(limit.toLong()).executeAsList().map { OutboxEntry(it.device_seq, it.event_id, it.event_type, it.json) }

    override fun pendingCount(): Long = q.countPendingOutbox().executeAsOne()

    override fun acknowledgeUpTo(deviceSeq: Long, atMillis: Long) {
        q.acknowledgeOutboxUpTo(atMillis, deviceSeq)
    }

    override fun purgeAcknowledged(beforeMillis: Long): Long = q.purgeAcknowledged(beforeMillis).value

    override fun outboxHolds(deviceSeq: Long): Boolean = q.countOutboxSeq(deviceSeq).executeAsOne() > 0

    override fun unacknowledgeFrom(deviceSeq: Long) {
        q.unacknowledgeFrom(deviceSeq)
    }

    override fun maxOutboxSeq(): Long? = q.maxOutboxSeq().executeAsOne().seq

    override fun saveSession(session: SessionRecord) {
        q.upsertSession(session.sessionId, if (session.isOpen) 1 else 0, json.encodeToString(SessionRecord.serializer(), session))
    }

    override fun openSession(): SessionRecord? =
        q.selectOpenSession().executeAsOneOrNull()?.let { json.decodeFromString(SessionRecord.serializer(), it) }

    override fun session(sessionId: String): SessionRecord? =
        q.selectSession(sessionId).executeAsOneOrNull()?.let { json.decodeFromString(SessionRecord.serializer(), it) }

    override fun saveCashMovement(movement: CashMovementRecord) {
        q.insertCashMovement(
            id = movement.movementId,
            session_id = movement.sessionId,
            kind = movement.kind,
            amount = movement.amount.plain(),
            at = movement.occurredAt,
            operator_user_id = movement.operatorUserId,
            reason = movement.reason,
        )
    }

    override fun cashMovementsOfSession(sessionId: String): List<CashMovementRecord> =
        q.selectCashMovementsBySession(sessionId).executeAsList().map {
            CashMovementRecord(
                movementId = it.id,
                sessionId = it.session_id,
                kind = it.kind,
                amount = Money.parse(it.amount),
                occurredAt = it.at,
                operatorUserId = it.operator_user_id,
                reason = it.reason ?: "",
            )
        }

    override fun saveReceipt(receipt: IssuedReceipt) {
        q.insertReceipt(receipt.documentId, receipt.sessionId, receipt.number, json.encodeToString(IssuedReceipt.serializer(), receipt))
        for (t in receipt.tenders) {
            q.insertTender(
                tender_id = "${receipt.documentId}-${t.seq}",
                document_id = receipt.documentId,
                seq = t.seq.toLong(),
                kind = t.kind,
                amount = t.amount.plain(),
                reference = t.reference,
                customer_account_id = t.customerAccountId,
                change_given = if (t.changeGiven > Money.ZERO) t.changeGiven.plain() else null,
                offline = if (t.offline) 1L else 0L,
            )
        }
    }

    override fun receiptsOfSession(sessionId: String): List<IssuedReceipt> =
        q.selectReceiptsOfSession(sessionId).executeAsList().map { json.decodeFromString(IssuedReceipt.serializer(), it) }

    override fun maxReceiptNumber(): Long? = q.maxReceiptNumber().executeAsOne().number

    override fun saveParkedSale(sale: ParkedSaleRecord) {
        q.insertParkedSale(
            id = sale.id,
            session_id = sale.sessionId,
            lines_json = json.encodeToString(sale.lines),
            parked_at = sale.parkedAt,
            operator_user_id = sale.operatorUserId,
            customer_id = sale.beneficiaryIdentifier,
            notes = sale.customerClass,
        )
    }

    override fun parkedSale(id: String): ParkedSaleRecord? =
        q.selectParkedSale(id).executeAsOneOrNull()?.toParkedSale()

    override fun parkedSalesOfSession(sessionId: String): List<ParkedSaleRecord> =
        q.selectParkedSalesBySession(sessionId).executeAsList().map { it.toParkedSale() }

    override fun deleteParkedSale(id: String) {
        q.deleteParkedSale(id)
    }

    override fun addAnomaly(anomaly: Anomaly) {
        q.insertAnomaly(
            anomaly.kind, anomaly.deviceSeq, anomaly.eventId, anomaly.reason, anomaly.detail, anomaly.notedAt,
            if (anomaly.seen) 1 else 0,
        )
    }

    override fun anomalies(limit: Int): List<Anomaly> = q.selectAnomalies(limit.toLong()).executeAsList().map {
        Anomaly(it.kind, it.device_seq, it.event_id, it.reason, it.detail, it.noted_at, it.seen == 1L)
    }

    override fun unseenAnomalyCount(): Long = q.countUnseenAnomalies().executeAsOne()

    override fun markAnomaliesSeen() {
        q.markAnomaliesSeen()
    }

    override fun quarantinedAmong(deviceSeqs: Collection<Long>): Long =
        if (deviceSeqs.isEmpty()) 0 else q.countQuarantinedAmong(deviceSeqs).executeAsOne()

    override fun saveShopOperation(bundle: ShopOperationBundle) {
        appendOutbox(bundle.outboxEntry)
        for (line in bundle.lines) {
            q.insertDocumentLine(
                line_id = line.lineId,
                document_id = bundle.document.documentId,
                line_no = line.lineNo.toLong(),
                sku_id = line.skuId,
                batch_id = line.batchId,
                uom_code = line.uomCode,
                qty = line.qty.plain(),
                unit_price = line.unitPrice.plain(),
                mrp_applied = null,
                control_price_applied = null,
                cap_reason = null,
                discount_rule_id = null,
                discount_amount = "0.00",
                tax_rate_percent = "0.00",
                tax_amount = "0.00",
                line_total = line.lineTotal.plain(),
                unit_cost_at_issue = line.unitCostAtIssue?.plain(),
                loss_category = line.lossCategory,
                reference_line_id = line.referenceLineId,
                negative_ack = 0L,
                picked = 0L,
                weight_kg = null,
            )
        }
        for (h in bundle.stateHistory) {
            q.insertStateHistory(
                history_id = h.historyId,
                document_id = h.documentId,
                from_status = h.fromStatus,
                to_status = h.toStatus,
                occurred_at = h.occurredAt,
                occurred_local = h.occurredLocal,
                actor_user_id = h.actorUserId,
                reason_code = h.reasonCode,
                reason_text = h.reasonText,
            )
        }
        for (m in bundle.stockMovements) {
            q.insertStockMovement(
                movement_id = m.movementId,
                movement_seq = m.movementSeq,
                location_id = m.locationId,
                sku_id = m.skuId,
                batch_id = m.batchId,
                lot_id = m.lotId,
                condition = m.condition,
                qty_delta = m.qtyDelta,
                movement_type = m.movementType,
                unit_cost_at_movement = m.unitCostAtMovement?.plain(),
                document_id = m.documentId,
                document_line_id = m.documentLineId,
                occurred_at = m.occurredAt,
                occurred_local = m.occurredLocal,
                operator_user_id = m.operatorUserId,
                device_id = m.deviceId,
            )
        }
        for (a in bundle.auditEvents) {
            q.insertAuditEvent(
                audit_id = a.auditId,
                event_type_code = a.eventTypeCode,
                occurred_at = a.occurredAt,
                occurred_local = a.occurredLocal,
                owner_entity_id = a.ownerEntityId,
                location_id = a.locationId,
                actor_user_id = a.actorUserId,
                device_id = a.deviceId,
                till_position_id = a.tillPositionId,
                subject_table = a.subjectTable,
                subject_id = a.subjectId,
                document_id = a.documentId,
                before_state = a.beforeState,
                after_state = a.afterState,
                reason_code = a.reasonCode,
                reason_text = a.reasonText,
                device_seq = a.deviceSeq,
                correlation_id = a.correlationId,
            )
        }
    }

    override fun documentLines(documentId: String): List<ShopDocumentLine> =
        q.selectDocumentLinesByDocument(documentId).executeAsList().map { row ->
            ShopDocumentLine(
                lineNo = row.line_no.toInt(),
                lineId = row.line_id,
                skuId = row.sku_id,
                batchId = row.batch_id,
                uomCode = row.uom_code,
                qty = Qty.parse(row.qty),
                unitPrice = Money.parse(row.unit_price),
                lineTotal = Money.parse(row.line_total),
                unitCostAtIssue = row.unit_cost_at_issue?.let { Money.parse(it) },
                lossCategory = row.loss_category,
                referenceLineId = row.reference_line_id,
            )
        }

    override fun stateHistoryOf(documentId: String): List<ShopStateHistory> =
        q.selectStateHistoryByDocument(documentId).executeAsList().map { row ->
            ShopStateHistory(
                historyId = row.history_id,
                documentId = row.document_id,
                fromStatus = row.from_status,
                toStatus = row.to_status,
                occurredAt = row.occurred_at,
                occurredLocal = row.occurred_local.orEmpty(),
                actorUserId = row.actor_user_id,
                reasonCode = row.reason_code,
                reasonText = row.reason_text,
            )
        }

    override fun stockMovementsOf(documentId: String): List<ShopStockMovement> =
        q.selectStockMovementsByDocument(documentId).executeAsList().map { row ->
            ShopStockMovement(
                movementId = row.movement_id,
                movementSeq = row.movement_seq,
                locationId = row.location_id,
                skuId = row.sku_id,
                batchId = row.batch_id,
                lotId = row.lot_id,
                condition = row.condition,
                qtyDelta = row.qty_delta,
                movementType = row.movement_type,
                unitCostAtMovement = row.unit_cost_at_movement?.let { Money.parse(it) },
                documentId = row.document_id,
                documentLineId = row.document_line_id,
                occurredAt = row.occurred_at,
                occurredLocal = row.occurred_local.orEmpty(),
                operatorUserId = row.operator_user_id,
                deviceId = row.device_id,
            )
        }

    override fun auditEventsOf(documentId: String): List<ShopAuditEvent> =
        q.selectAuditByDocument(documentId).executeAsList().map { row ->
            ShopAuditEvent(
                auditId = row.audit_id,
                eventTypeCode = row.event_type_code,
                occurredAt = row.occurred_at,
                occurredLocal = row.occurred_local.orEmpty(),
                ownerEntityId = row.owner_entity_id,
                locationId = row.location_id,
                actorUserId = row.actor_user_id,
                deviceId = row.device_id,
                tillPositionId = row.till_position_id,
                subjectTable = row.subject_table,
                subjectId = row.subject_id,
                documentId = row.document_id,
                beforeState = row.before_state,
                afterState = row.after_state,
                reasonCode = row.reason_code,
                reasonText = row.reason_text,
                deviceSeq = row.device_seq,
                correlationId = row.correlation_id,
            )
        }

    private companion object {
        const val SNAPSHOT_VERSION = "snapshot_version"
    }

    private fun Parked_sales.toParkedSale() = ParkedSaleRecord(
        id = id,
        sessionId = requireNotNull(session_id),
        lines = json.decodeFromString<List<ParkedSaleLine>>(lines_json),
        parkedAt = parked_at,
        operatorUserId = operator_user_id,
        beneficiaryIdentifier = customer_id,
        customerClass = notes,
    )
}
