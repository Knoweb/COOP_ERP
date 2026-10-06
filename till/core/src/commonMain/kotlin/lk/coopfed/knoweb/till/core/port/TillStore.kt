package lk.coopfed.knoweb.till.core.port

import lk.coopfed.knoweb.till.core.model.Anomaly
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
    fun removeSetting(key: String)

    fun loadSnapshot(): SnapshotState
    /** Replaces the whole snapshot, live and held rows and the version, in one step. */
    fun saveSnapshot(state: SnapshotState)

    fun appendOutbox(entry: OutboxEntry)
    /** The oldest facts central has not acknowledged, in device sequence order. */
    fun pendingOutbox(limit: Int): List<OutboxEntry>
    /** How many facts central has not acknowledged. */
    fun pendingCount(): Long
    /**
     * Central holds everything at or below [deviceSeq] (doc 32 section 3.3): those rows are marked
     * acknowledged at [atMillis] and kept for the retention window (doc 32 section 3.4; DR-3).
     */
    fun acknowledgeUpTo(deviceSeq: Long, atMillis: Long)
    /** Removes acknowledged rows acknowledged before [beforeMillis]; never a row central has not acknowledged. */
    fun purgeAcknowledged(beforeMillis: Long): Long
    /** Whether the outbox still holds the row with [deviceSeq] (acknowledged or not). */
    fun outboxHolds(deviceSeq: Long): Boolean
    /** Makes every row from [deviceSeq] on pending again, to be sent again (RESEND_FROM, 409 sequence gap). */
    fun unacknowledgeFrom(deviceSeq: Long)
    /** The highest device sequence the outbox holds, or null when it is empty. */
    fun maxOutboxSeq(): Long?

    fun saveSession(session: SessionRecord)
    fun openSession(): SessionRecord?
    fun session(sessionId: String): SessionRecord?

    fun saveReceipt(receipt: IssuedReceipt)
    fun receiptsOfSession(sessionId: String): List<IssuedReceipt>
    /** The highest receipt number issued, or null when none is. */
    fun maxReceiptNumber(): Long?

    /** Records a problem; the same [Anomaly.kind] for the same device sequence is recorded once. */
    fun addAnomaly(anomaly: Anomaly)
    fun anomalies(limit: Int): List<Anomaly>
    fun unseenAnomalyCount(): Long
    fun markAnomaliesSeen()
    /** How many facts with these device sequences central quarantined. */
    fun quarantinedAmong(deviceSeqs: Collection<Long>): Long
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
    /** The till's raw clock (epoch ms) when the offset was measured (TWK-07). */
    const val CLOCK_OFFSET_AT = "clock_offset_at"
    /** central's server_time (epoch ms) at the last ack or heartbeat. */
    const val LAST_SERVER_TIME = "last_server_time"
    const val LAST_SYNC_AT = "last_sync_at"
    /** The till's clock (epoch ms) at the last verified snapshot answer (doc 32 section 5.2, staleness). */
    const val LAST_SNAPSHOT_AT = "last_snapshot_at"
    /** The till's clock (epoch ms) at enrolment: a revoke issued before it is an old one. */
    const val ENROLLED_AT = "enrolled_at"
    /** Wrong PINs in a row at this till, and the lock-out's end (epoch ms), 26A section 8 (TWK-03). */
    const val PIN_FAILURES = "pin_failures"
    const val PIN_LOCKED_UNTIL = "pin_locked_until"
    /** "true" once a snapshot has carried an operator: the trial cashier is never offered again (TWK-04). */
    const val OPERATORS_SEEN = "operators_seen"
    const val TRIAL_OPERATOR_ID = "trial_operator_id"
    /** The verified revoke instruction (JSON) while the till is revoked (doc 32 section 9). */
    const val REVOKED = "revoked"
    /** central's FLOOR_NOTICE detail while the till's version is below the floor. */
    const val FLOOR_NOTICE = "floor_notice"
    /** Why uploading stopped (central asked for facts the till no longer holds). */
    const val SYNC_STOPPED = "sync_stopped"
}
