package lk.coopfed.knoweb.till.db

import app.cash.sqldelight.db.SqlDriver
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import lk.coopfed.knoweb.till.core.model.Anomaly
import lk.coopfed.knoweb.till.core.model.IssuedReceipt
import lk.coopfed.knoweb.till.core.model.OutboxEntry
import lk.coopfed.knoweb.till.core.model.SessionRecord
import lk.coopfed.knoweb.till.core.port.TillStore
import lk.coopfed.knoweb.till.core.snapshot.SnapshotRow
import lk.coopfed.knoweb.till.core.snapshot.SnapshotState

/** The [TillStore] port on the SQLDelight database; the same code on every platform, only the driver differs. */
class SqlTillStore(driver: SqlDriver) : TillStore {

    private val db = TillDatabase(driver)
    private val q = db.tillQueries
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

    override fun saveReceipt(receipt: IssuedReceipt) {
        q.insertReceipt(receipt.documentId, receipt.sessionId, receipt.number, json.encodeToString(IssuedReceipt.serializer(), receipt))
    }

    override fun receiptsOfSession(sessionId: String): List<IssuedReceipt> =
        q.selectReceiptsOfSession(sessionId).executeAsList().map { json.decodeFromString(IssuedReceipt.serializer(), it) }

    override fun maxReceiptNumber(): Long? = q.maxReceiptNumber().executeAsOne().number

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

    private companion object {
        const val SNAPSHOT_VERSION = "snapshot_version"
    }
}
