package lk.coopfed.knoweb.till.core.port

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import lk.coopfed.knoweb.till.core.model.DeviceIdentity

/** Central could not be reached (no network, a timeout, a 5xx): the till carries on offline. */
class CentralUnreachable(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Central answered with a refusal the till cannot go on from by itself (a 4xx other than 429). */
class CentralRefused(val status: Int, val problem: String) : Exception("central answered $status: $problem") {
    /** The problem document (common.yaml Problem), when the body is one. */
    val document: JsonObject? by lazy { runCatching { Json.parseToJsonElement(problem) as? JsonObject }.getOrNull() }

    /** The problem's stable code, e.g. sync.sequence_gap. */
    val code: String? get() = (document?.get("code") as? JsonPrimitive)?.contentOrNull

    /** The problem's params, or an empty object. */
    val params: JsonObject get() = document?.get("params") as? JsonObject ?: JsonObject(emptyMap())
}

/** What became of one event of a batch (sync.yaml EventOutcome). */
data class EventOutcome(val deviceSeq: Long, val eventId: String?, val outcome: String, val reason: String?) {
    companion object {
        const val QUARANTINED = "QUARANTINED"
    }
}

/** Something central asks the till to do (sync.yaml Instruction): RESEND_FROM, FLOOR_NOTICE ... */
data class Instruction(val type: String, val fromSeq: Long?, val detail: String?) {
    companion object {
        const val RESEND_FROM = "RESEND_FROM"
        const val FLOOR_NOTICE = "FLOOR_NOTICE"
    }
}

/** What central answered to a batch (sync.yaml SyncAck, or 429). */
sealed interface UploadAnswer {
    data class Acknowledged(
        val lastAppliedSeq: Long,
        val snapshotVersion: Long,
        val clockOffsetMs: Long?,
        val outcomes: List<EventOutcome> = emptyList(),
        val instructions: List<Instruction> = emptyList(),
        val serverTime: Instant? = null,
    ) : UploadAnswer
    data class RateLimited(val retryAfterSeconds: Long) : UploadAnswer
}

/**
 * The sync contract as the till uses it (doc 32; openapi/sync.yaml), implemented in module sync
 * over HTTP. The till never calls anything else at central.
 */
interface Central {
    /** POST /v1/sync/devices/{id}/enrol with the one-time code; the answer as central gave it. */
    suspend fun enrol(serverUrl: String, deviceId: String, code: String, hardwareSerial: String, appVersion: String): JsonObject

    /** From here on, calls carry a device token for this identity. */
    fun identify(device: DeviceIdentity)

    /** GET /v1/sync/locations/{id}/snapshot?since=... */
    suspend fun snapshot(since: Long): JsonObject

    /** POST /v1/sync/devices/{id}/batches (gzip, doc 32 section 3.2) */
    suspend fun upload(batch: JsonObject): UploadAnswer

    /** POST /v1/sync/devices/{id}/heartbeat; the answer as central gave it (sync.yaml HeartbeatResponse). */
    suspend fun heartbeat(report: JsonObject): JsonObject
}
