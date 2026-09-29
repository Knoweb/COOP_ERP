package lk.coopfed.knoweb.till.core.port

import lk.coopfed.knoweb.till.core.model.IssuedReceipt
import lk.coopfed.knoweb.till.core.model.OutboxEntry
import lk.coopfed.knoweb.till.core.model.SessionRecord
import lk.coopfed.knoweb.till.core.snapshot.SnapshotState

/**
 * The till's local database (SQLDelight over encrypted SQLite, module db). Every fact and the
 * counters that number it are written in one [transaction], so a power cut never leaves a receipt
 * number used without its receipt, or a device sequence number without its outbox row.
 */
interface TillStore {
    fun <T> transaction(block: () -> T): T

    fun setting(key: String): String?
    fun putSetting(key: String, value: String)

    fun loadSnapshot(): SnapshotState
    /** Replaces the whole snapshot, live and held rows and the version, in one step. */
    fun saveSnapshot(state: SnapshotState)

    fun appendOutbox(entry: OutboxEntry)
    /** The oldest unacknowledged facts, in device sequence order. */
    fun pendingOutbox(limit: Int): List<OutboxEntry>
    fun pendingCount(): Long
    /** Central holds everything at or below [deviceSeq] (doc 32 section 3.3). */
    fun acknowledgeUpTo(deviceSeq: Long)

    fun saveSession(session: SessionRecord)
    fun openSession(): SessionRecord?
    fun session(sessionId: String): SessionRecord?

    fun saveReceipt(receipt: IssuedReceipt)
    fun receiptsOfSession(sessionId: String): List<IssuedReceipt>
}

/** Keys of the settings the till keeps. */
object Settings {
    const val DEVICE = "device"
    const val NEXT_DEVICE_SEQ = "next_device_seq"
    const val NEXT_RECEIPT_NUMBER = "next_receipt_number"
    const val BUSINESS_DATE = "business_date"
    /** The batch id of the batch in flight, with its range: the same id on a retry (sync.yaml SyncBatch.batch_id). */
    const val BATCH_IN_FLIGHT = "batch_in_flight"
    const val LAST_ACKNOWLEDGED = "last_acknowledged_seq"
    const val CLOCK_OFFSET_MS = "clock_offset_ms"
    const val LAST_SYNC_AT = "last_sync_at"
}
