package lk.coopfed.knoweb.till.core.port

import kotlinx.serialization.json.JsonObject
import lk.coopfed.knoweb.till.core.model.DeviceIdentity

/** Central could not be reached (no network, a timeout, a 5xx): the till carries on offline. */
class CentralUnreachable(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Central answered with a refusal the till cannot go on from by itself (a 4xx other than 429). */
class CentralRefused(val status: Int, val problem: String) : Exception("central answered $status: $problem")

/** What central answered to a batch (sync.yaml SyncAck, or 429). */
sealed interface UploadAnswer {
    data class Acknowledged(val lastAppliedSeq: Long, val snapshotVersion: Long, val clockOffsetMs: Long?) : UploadAnswer
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

    /** POST /v1/sync/devices/{id}/batches */
    suspend fun upload(batch: JsonObject): UploadAnswer

    /** POST /v1/sync/devices/{id}/heartbeat */
    suspend fun heartbeat(report: JsonObject): JsonObject
}
