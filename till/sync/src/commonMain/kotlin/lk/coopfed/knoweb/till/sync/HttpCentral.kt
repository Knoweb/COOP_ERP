package lk.coopfed.knoweb.till.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.parameters
import kotlin.time.Instant
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import lk.coopfed.knoweb.till.core.id.IdGenerator
import lk.coopfed.knoweb.till.core.model.DeviceIdentity
import lk.coopfed.knoweb.till.core.port.Central
import lk.coopfed.knoweb.till.core.port.CentralRefused
import lk.coopfed.knoweb.till.core.port.CentralUnreachable
import lk.coopfed.knoweb.till.core.port.UploadAnswer

/**
 * The sync contract over HTTP (openapi/sync.yaml), as the backend's till simulator speaks it. Each
 * call carries a device token from the identity provider (client_credentials with the credential
 * of the enrolment answer), fetched again when it is about to expire or central says 401. Every
 * POST carries an Idempotency-Key. A network failure or a 5xx is [CentralUnreachable]: the till
 * carries on offline.
 */
class HttpCentral(
    private val http: HttpClient,
    private val ids: IdGenerator,
    private val now: () -> Instant,
    /** Replaces the token endpoint of the enrolment answer (a till that reaches the identity provider by another address). */
    private val tokenEndpointOverride: String? = null,
) : Central {

    private val json = Json { ignoreUnknownKeys = true }
    private var device: DeviceIdentity? = null
    private var token: String? = null
    private var tokenExpires: Instant = Instant.DISTANT_PAST

    override suspend fun enrol(serverUrl: String, deviceId: String, code: String, hardwareSerial: String, appVersion: String): JsonObject {
        val body = buildJsonObject {
            put("enrolment_code", code)
            put("hardware_serial", hardwareSerial)
            put("app_version", appVersion)
        }
        val answer = call {
            http.post("$serverUrl/v1/sync/devices/$deviceId/enrol") {
                contentType(ContentType.Application.Json)
                header("Idempotency-Key", ids.next())
                setBody(body.toString())
            }
        }
        return json.parseToJsonElement(ok(answer)).jsonObject
    }

    override fun identify(device: DeviceIdentity) {
        if (this.device?.clientSecret != device.clientSecret) {
            token = null
        }
        this.device = device
    }

    override suspend fun snapshot(since: Long): JsonObject {
        val d = requireDevice()
        val answer = authorised { bearer ->
            http.get("${d.serverUrl}/v1/sync/locations/${d.locationId}/snapshot?since=$since") {
                header(HttpHeaders.Authorization, "Bearer $bearer")
            }
        }
        return json.parseToJsonElement(ok(answer)).jsonObject
    }

    override suspend fun upload(batch: JsonObject): UploadAnswer {
        val d = requireDevice()
        val answer = authorised { bearer ->
            http.post("${d.serverUrl}/v1/sync/devices/${d.deviceId}/batches") {
                header(HttpHeaders.Authorization, "Bearer $bearer")
                header("Idempotency-Key", ids.next())
                contentType(ContentType.Application.Json)
                setBody(batch.toString())
            }
        }
        if (answer.status.value == 429) {
            val problem = runCatching { json.parseToJsonElement(answer.bodyAsText()).jsonObject }.getOrNull()
            val wait = problem?.get("params")?.jsonObject?.get("retry_after")?.jsonPrimitive?.longOrNull ?: 1
            return UploadAnswer.RateLimited(wait)
        }
        val ack = json.parseToJsonElement(ok(answer)).jsonObject
        return UploadAnswer.Acknowledged(
            lastAppliedSeq = ack.getValue("last_applied_seq").jsonPrimitive.longOrNull!!,
            snapshotVersion = ack["snapshot_version"]?.jsonPrimitive?.longOrNull ?: 0,
            clockOffsetMs = ack["clock_offset_ms"]?.jsonPrimitive?.longOrNull,
        )
    }

    override suspend fun heartbeat(report: JsonObject): JsonObject {
        val d = requireDevice()
        val answer = authorised { bearer ->
            http.post("${d.serverUrl}/v1/sync/devices/${d.deviceId}/heartbeat") {
                header(HttpHeaders.Authorization, "Bearer $bearer")
                header("Idempotency-Key", ids.next())
                contentType(ContentType.Application.Json)
                setBody(report.toString())
            }
        }
        return json.parseToJsonElement(ok(answer)).jsonObject
    }

    // ---- the device token (doc 19 section 2.1) ----

    private suspend fun authorised(send: suspend (String) -> HttpResponse): HttpResponse {
        val first = call { send(bearer()) }
        if (first.status.value != 401) return first
        token = null
        return call { send(bearer()) }
    }

    private suspend fun bearer(): String {
        val current = token
        if (current != null && now() < tokenExpires) return current
        val d = requireDevice()
        val answer = call {
            http.submitForm(
                url = tokenEndpointOverride ?: d.tokenEndpoint,
                formParameters = parameters {
                    append("grant_type", "client_credentials")
                    append("client_id", d.clientId)
                    append("client_secret", d.clientSecret)
                },
            )
        }
        val body = json.parseToJsonElement(ok(answer)).jsonObject
        val fresh = body["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw CentralRefused(answer.status.value, "the identity provider gave no access token")
        val lifetime = body["expires_in"]?.jsonPrimitive?.longOrNull ?: 60
        token = fresh
        // Ask again 30 s before it runs out.
        tokenExpires = Instant.fromEpochSeconds(now().epochSeconds + (lifetime - 30).coerceAtLeast(5))
        return fresh
    }

    // ---- errors ----

    private fun requireDevice() = device ?: throw IllegalStateException("The till is not enrolled")

    private suspend fun call(block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: IOException) {
        throw CentralUnreachable(e.message ?: e::class.simpleName ?: "network failure", e)
    } catch (e: Exception) {
        if (e is CentralRefused || e is CentralUnreachable || e is kotlinx.coroutines.CancellationException) throw e
        // Ktor's engines report timeouts and refused connections with their own exception types.
        throw CentralUnreachable(e.message ?: e::class.simpleName ?: "network failure", e)
    }

    private suspend fun ok(answer: HttpResponse): String {
        val text = answer.bodyAsText()
        val status = answer.status.value
        return when {
            status in 200..299 -> text
            status >= 500 -> throw CentralUnreachable("central answered $status")
            else -> throw CentralRefused(status, text)
        }
    }
}
