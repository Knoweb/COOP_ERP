package lk.coopfed.knoweb.till.core

import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import lk.coopfed.knoweb.till.core.crypto.Base64
import lk.coopfed.knoweb.till.core.crypto.Sha256
import lk.coopfed.knoweb.till.core.id.IdGenerator
import lk.coopfed.knoweb.till.core.model.Anomaly
import lk.coopfed.knoweb.till.core.model.DeviceIdentity
import lk.coopfed.knoweb.till.core.model.IssuedReceipt
import lk.coopfed.knoweb.till.core.model.OutboxEntry
import lk.coopfed.knoweb.till.core.model.SessionRecord
import lk.coopfed.knoweb.till.core.port.Central
import lk.coopfed.knoweb.till.core.port.CentralRefused
import lk.coopfed.knoweb.till.core.port.CentralUnreachable
import lk.coopfed.knoweb.till.core.port.EventOutcome
import lk.coopfed.knoweb.till.core.port.Instruction
import lk.coopfed.knoweb.till.core.port.SignatureVerifier
import lk.coopfed.knoweb.till.core.port.TillClock
import lk.coopfed.knoweb.till.core.port.TillStore
import lk.coopfed.knoweb.till.core.port.UploadAnswer
import lk.coopfed.knoweb.till.core.snapshot.SnapshotRow
import lk.coopfed.knoweb.till.core.snapshot.SnapshotState
import lk.coopfed.knoweb.till.core.snapshot.SnapshotTableDelta
import lk.coopfed.knoweb.till.core.snapshot.SnapshotVerifier

/** A database in memory, with a transaction that really rolls back. */
class InMemoryStore : TillStore {
    private data class Data(
        val settings: MutableMap<String, String> = mutableMapOf(),
        var snapshot: SnapshotState = SnapshotState.EMPTY,
        val outbox: MutableMap<Long, OutboxEntry> = sortedMapOf<Long, OutboxEntry>().toMutableMap(),
        /** device_seq to acked_at, for the acknowledged rows. */
        val acked: MutableMap<Long, Long> = mutableMapOf(),
        val sessions: MutableMap<String, SessionRecord> = linkedMapOf(),
        val receipts: MutableList<IssuedReceipt> = mutableListOf(),
        val anomalies: MutableList<Anomaly> = mutableListOf(),
    ) {
        fun copy() = Data(
            settings.toMutableMap(), snapshot, outbox.toMutableMap(), acked.toMutableMap(), sessions.toMutableMap(),
            receipts.toMutableList(), anomalies.toMutableList(),
        )
    }

    private var data = Data()
    private var depth = 0
    /** Makes the next transaction fail at its end, as a power cut would. */
    var failNextCommit = false

    override fun <T> transaction(block: () -> T): T {
        val before = if (depth == 0) data.copy() else null
        depth++
        try {
            val result = block()
            if (depth == 1 && failNextCommit) {
                failNextCommit = false
                throw IllegalStateException("power cut")
            }
            return result
        } catch (e: Throwable) {
            if (before != null) data = before
            throw e
        } finally {
            depth--
        }
    }

    override fun setting(key: String) = data.settings[key]
    override fun putSetting(key: String, value: String) { data.settings[key] = value }
    override fun removeSetting(key: String) { data.settings.remove(key) }
    override fun loadSnapshot() = data.snapshot
    override fun saveSnapshot(state: SnapshotState) { data.snapshot = state }
    override fun appendOutbox(entry: OutboxEntry) {
        check(entry.deviceSeq !in data.outbox) { "device_seq ${entry.deviceSeq} used twice" }
        data.outbox[entry.deviceSeq] = entry
    }
    override fun pendingOutbox(limit: Int) = data.outbox.values.filter { it.deviceSeq !in data.acked }.sortedBy { it.deviceSeq }.take(limit)
    override fun pendingCount() = data.outbox.keys.count { it !in data.acked }.toLong()
    override fun acknowledgeUpTo(deviceSeq: Long, atMillis: Long) {
        data.outbox.keys.filter { it <= deviceSeq && it !in data.acked }.forEach { data.acked[it] = atMillis }
    }
    override fun purgeAcknowledged(beforeMillis: Long): Long {
        val gone = data.acked.filterValues { it < beforeMillis }.keys
        gone.forEach { data.outbox.remove(it); data.acked.remove(it) }
        return gone.size.toLong()
    }
    override fun outboxHolds(deviceSeq: Long) = deviceSeq in data.outbox
    override fun unacknowledgeFrom(deviceSeq: Long) { data.acked.keys.removeAll { it >= deviceSeq } }
    override fun maxOutboxSeq() = data.outbox.keys.maxOrNull()
    override fun saveSession(session: SessionRecord) { data.sessions[session.sessionId] = session }
    override fun openSession() = data.sessions.values.firstOrNull { it.isOpen }
    override fun session(sessionId: String) = data.sessions[sessionId]
    override fun saveReceipt(receipt: IssuedReceipt) {
        check(data.receipts.none { it.number == receipt.number }) { "receipt number ${receipt.number} used twice" }
        data.receipts += receipt
    }
    override fun receiptsOfSession(sessionId: String) = data.receipts.filter { it.sessionId == sessionId }
    override fun maxReceiptNumber() = data.receipts.maxOfOrNull { it.number }
    override fun addAnomaly(anomaly: Anomaly) {
        if (anomaly.deviceSeq != null && data.anomalies.any { it.kind == anomaly.kind && it.deviceSeq == anomaly.deviceSeq }) return
        data.anomalies += anomaly
    }
    override fun anomalies(limit: Int) = data.anomalies.reversed().take(limit)
    override fun unseenAnomalyCount() = data.anomalies.count { !it.seen }.toLong()
    override fun markAnomaliesSeen() { data.anomalies.replaceAll { it.copy(seen = true) } }
    override fun quarantinedAmong(deviceSeqs: Collection<Long>) =
        data.anomalies.count { it.kind == Anomaly.QUARANTINED && it.deviceSeq in deviceSeqs }.toLong()

    /** Every outbox row, acknowledged or not, for the tests. */
    fun outboxSeqs(): List<Long> = data.outbox.keys.sorted()
}

/**
 * Stands in for Ed25519 in common tests: a "signature" is SHA-256 of the key text and the message.
 * The real Ed25519 check is tested on the JVM (sync module) with the JDK's EdDSA.
 */
object FakeSignatures : SignatureVerifier {
    fun sign(publicKey: String, message: String): String =
        Base64.encode(Sha256.digest((publicKey + "|" + message).encodeToByteArray()))

    override fun verifyEd25519(publicKey: String, message: ByteArray, signature: ByteArray): Boolean =
        Base64.encode(signature) == sign(publicKey, message.decodeToString())
}

/**
 * The till's clock in a test. [advance] is time that really passes (the wall clock and the
 * monotonic clock move together); [setWallClock] is someone setting the PC's clock by hand.
 */
class FixedClock(var instant: Instant = Instant.parse("2026-09-29T04:30:00Z")) : TillClock {
    private var elapsed = 0L
    override fun now() = instant
    override val zone: TimeZone = TimeZone.of("Asia/Colombo")
    override fun elapsedMillis(): Long = elapsed
    fun advance(by: Duration) {
        instant += by
        elapsed += by.inWholeMilliseconds
    }
    fun setWallClock(to: Instant) { instant = to }
}

class CountingIds(private val prefix: String = "00000000-0000-7000-8000-") : IdGenerator {
    private var n = 0
    override fun next(): String = prefix + (++n).toString().padStart(12, '0')
}

/** Central as the till sees it: enrols, serves one snapshot, takes batches, or is unreachable. */
class FakeCentral(var snapshotAnswer: JsonObject? = null) : Central {
    var reachable = true
    val batches = mutableListOf<JsonObject>()
    var enrolAnswer: JsonObject = Samples.enrolment()
    /** How many events of a batch central applies (null: all of them). */
    var applyAtMost: Int? = null
    /** Central takes the next batch but the answer never reaches the till. */
    var loseNextAnswer = false
    /** The snapshot answer for a given since, when set (else [snapshotAnswer]). */
    var snapshotFor: ((Long) -> JsonObject)? = null
    val snapshotRequests = mutableListOf<Long>()
    /** Refusals the next uploads answer with, in order. */
    val uploadRefusals = ArrayDeque<CentralRefused>()
    /** The outcomes of an ack, by device sequence (APPLIED when absent). */
    val outcomes = mutableMapOf<Long, EventOutcome>()
    /** Instructions the next ack carries, once. */
    val nextInstructions = mutableListOf<Instruction>()
    var clockOffsetMs: Long? = 0
    var serverTime: Instant? = null
    var heartbeatAnswer: JsonObject = buildJsonObject { put("snapshot_version", 0) }
    var heartbeatRefusal: CentralRefused? = null
    val heartbeats = mutableListOf<JsonObject>()

    override suspend fun enrol(serverUrl: String, deviceId: String, code: String, hardwareSerial: String, appVersion: String): JsonObject {
        if (!reachable) throw CentralUnreachable("no route to central")
        return enrolAnswer
    }

    override fun identify(device: DeviceIdentity) {}

    override suspend fun snapshot(since: Long): JsonObject {
        if (!reachable) throw CentralUnreachable("no route to central")
        snapshotRequests += since
        return snapshotFor?.invoke(since) ?: snapshotAnswer ?: error("no snapshot prepared")
    }

    override suspend fun upload(batch: JsonObject): UploadAnswer {
        if (!reachable) throw CentralUnreachable("no route to central")
        batches += batch
        uploadRefusals.removeFirstOrNull()?.let { throw it }
        if (loseNextAnswer) {
            loseNextAnswer = false
            throw CentralUnreachable("the answer was lost on the way back")
        }
        val events = batch.getValue("events").jsonArray
        val first = batch.getValue("first_seq").jsonPrimitive.long
        val applied = minOf(events.size, applyAtMost ?: events.size)
        val last = first + applied - 1
        val instructions = nextInstructions.toList().also { nextInstructions.clear() }
        return UploadAnswer.Acknowledged(
            last, 1, clockOffsetMs,
            outcomes = (first..last).map { outcomes[it] ?: EventOutcome(it, null, "APPLIED", null) },
            instructions = instructions,
            serverTime = serverTime,
        )
    }

    override suspend fun heartbeat(report: JsonObject): JsonObject {
        if (!reachable) throw CentralUnreachable("no route to central")
        heartbeats += report
        heartbeatRefusal?.let { throw it }
        return heartbeatAnswer
    }
}

object Samples {
    const val LOCATION = "0190f0de-0000-7000-8000-000000000132"
    const val PUBLIC_KEY = "test-public-key"
    const val SKU_RICE = "0190f0de-0000-7000-8000-0000000a0001"
    const val SKU_DHAL = "0190f0de-0000-7000-8000-0000000a0002"
    const val OPERATOR = "0190f0de-0000-7000-8000-0000000b0001"

    fun enrolment(nextSeq: Long = 1, nextNumber: Long = 1, tokenEndpoint: String = "http://localhost/token"): JsonObject = buildJsonObject {
        put("device_id", "0190f0de-0000-7000-8000-0000000c0001")
        put("owner_entity_id", "0190f0de-0000-7000-8000-0000000000e3")
        put("location_id", LOCATION)
        put("till_position_id", "0190f0de-0000-7000-8000-0000000d0002")
        put("position_no", 2)
        put("primary_till", false)
        putJsonObject("credential") {
            put("client_id", "device-test")
            put("client_secret", "not-a-secret")
            put("token_endpoint", tokenEndpoint)
        }
        put("next_device_seq", nextSeq)
        putJsonArray("series") {
            add(buildJsonObject {
                put("series_id", "0190f0de-0000-7000-8000-0000000e0001")
                put("doc_type_code", "RCT")
                put("scope", "TILL_POSITION")
                put("prefix", "S01-T2")
                put("next_number", nextNumber)
            })
        }
        putJsonObject("snapshot") { put("version", 0); put("full_snapshot_required", true) }
        putJsonObject("signing_key") { put("key_id", "k1"); put("algorithm", "Ed25519"); put("public_key", PUBLIC_KEY) }
    }

    fun sku(id: String, en: String, si: String, barcode: String): SnapshotRow = SnapshotRow(
        "sku", id, null,
        buildJsonObject {
            put("sku_code", en.uppercase().take(4))
            put("status", "SHARED")
            put("short_name_en", en)
            put("short_name_si", si)
            put("short_name_ta", null as String?)
            put("base_uom_code", "EA")
            put("sold_by_weight", false)
            putJsonArray("barcodes") { add(buildJsonObject { put("barcode", barcode) }) }
        },
    )

    fun price(sku: String, price: String) = SnapshotRow(
        "price", "p-$sku", null, buildJsonObject { put("sku_id", sku); put("unit_price", price) },
    )

    fun operator(pinHash: String, id: String = OPERATOR, name: String = "Nimali", permissions: List<String> = emptyList()) = SnapshotRow(
        "operator", id, null,
        buildJsonObject {
            put("display_name", name)
            put("language", "si")
            put("pin_hash", pinHash)
            putJsonArray("permissions") { permissions.forEach { add(JsonPrimitive(it)) } }
        },
    )

    fun location() = SnapshotRow(
        "location", LOCATION, null,
        buildJsonObject {
            put("location_code", "S01")
            put("name_en", "Kuliyapitiya town shop")
            put("name_si", "කුලියාපිටිය නගර වෙළඳසැල")
            put("language", "si")
        },
    )

    /** A signed snapshot answer built by the manifest rules, from [rows]: full, or a delta from [since]. */
    fun snapshotAnswer(
        version: Long,
        rows: List<SnapshotRow>,
        location: String = LOCATION,
        since: Long = 0,
        full: Boolean = true,
        keyId: String = "k1",
    ): JsonObject {
        val tables = rows.groupBy { it.table }.mapValues { (_, r) ->
            SnapshotTableDelta(r.filter { it.data != null }, r.filter { it.data == null })
        }
        val manifest = buildJsonObject {
            put("full", full)
            put("location_id", location)
            put("since", since)
            putJsonObject("tables") {
                for ((name, t) in tables.toSortedMap()) {
                    putJsonObject(name) {
                        put("sha256", SnapshotVerifier.tableHash(t))
                        put("tombstones", t.tombstones.size)
                        put("upserts", t.upserts.size)
                    }
                }
            }
            put("version", version)
        }.toString()
        return buildJsonObject {
            put("location_id", location)
            put("since", since)
            put("version", version)
            put("full_snapshot_required", full)
            put("urgent", false)
            putJsonObject("tables") {
                for ((name, t) in tables) {
                    putJsonObject(name) {
                        put("upserts", JsonArray(t.upserts.map { row ->
                            buildJsonObject { put("row_id", row.rowId); put("apply_from", row.applyFrom?.toString()); put("data", row.data!!) }
                        }))
                        put("tombstones", buildJsonArray {
                            t.tombstones.forEach { row -> add(buildJsonObject { put("row_id", row.rowId) }) }
                        })
                    }
                }
            }
            put("manifest", manifest)
            put("signature", FakeSignatures.sign(PUBLIC_KEY, manifest))
            put("key_id", keyId)
        }
    }

    /** central's revoke instruction (DeviceAuth.revoke) as a 403 problem, signed with [signingKey]. */
    fun revoke(
        deviceId: String = DEVICE,
        issuedAt: String = "2026-09-29T05:00:00Z",
        signingKey: String = PUBLIC_KEY,
        status: String = "SUSPENDED",
    ): CentralRefused {
        val text = "type=REVOKE\ndevice_id=$deviceId\nstatus=$status\nissued_at=$issuedAt\n"
        val problem = buildJsonObject {
            put("status", 403)
            put("code", "sync.device_suspended")
            putJsonObject("params") {
                putJsonObject("revoke") {
                    put("type", "REVOKE")
                    put("device_id", deviceId)
                    put("status", status)
                    put("issued_at", issuedAt)
                    put("key_id", "k1")
                    put("signature", FakeSignatures.sign(signingKey, text))
                }
            }
        }
        return CentralRefused(403, problem.toString())
    }

    /** 409 sync.sequence_gap: central's cursor expects [expected]. */
    fun sequenceGap(expected: Long) = CentralRefused(
        409,
        buildJsonObject {
            put("status", 409)
            put("code", "sync.sequence_gap")
            putJsonObject("params") { put("expected_seq", expected) }
        }.toString(),
    )

    const val DEVICE = "0190f0de-0000-7000-8000-0000000c0001"

    /** The same answer with one value in one row changed on the way (the manifest untouched). */
    fun tampered(answer: JsonObject): JsonObject {
        val text = Json.encodeToString(JsonObject.serializer(), answer).replace("\"Rice 5kg\"", "\"Rice 1kg\"")
        return Json.parseToJsonElement(text).jsonObject
    }
}
