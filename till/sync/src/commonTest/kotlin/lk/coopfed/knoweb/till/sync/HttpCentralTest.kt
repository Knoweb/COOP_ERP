package lk.coopfed.knoweb.till.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.client.engine.mock.toByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lk.coopfed.knoweb.till.core.model.DeviceIdentity
import lk.coopfed.knoweb.till.core.port.CentralRefused
import lk.coopfed.knoweb.till.core.port.CentralUnreachable
import lk.coopfed.knoweb.till.core.port.EventOutcome
import lk.coopfed.knoweb.till.core.port.Instruction
import lk.coopfed.knoweb.till.core.port.UploadAnswer

class HttpCentralTest {

    private val device = DeviceIdentity(
        serverUrl = "http://central", deviceId = "dev-1", hardwareSerial = "DESKTOP-TRIAL-S01", ownerEntityId = "e",
        locationId = "loc", tillPositionId = "pos", positionNo = 2, primaryTill = false, clientId = "device-dev-1",
        clientSecret = "not-a-secret", tokenEndpoint = "http://idp/token", signingKeyId = "k", signingPublicKey = "pk",
        receiptSeriesId = "s", receiptPrefix = "S01-T2",
    )
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val calls = mutableListOf<String>()
    private val bodies = mutableListOf<Pair<String?, ByteArray>>()

    /** Stands in for gzip in common code: the real one (JvmGzip) is tested on the JVM. */
    private val fakeGzip: (ByteArray) -> ByteArray = { "GZ:".encodeToByteArray() + it }

    private fun central(batchAnswer: () -> Pair<HttpStatusCode, String>): HttpCentral {
        var n = 0
        val engine = MockEngine { request ->
            calls += "${request.method.value} ${request.url.encodedPath} ${request.headers[HttpHeaders.Authorization] ?: ""}".trim()
            when (request.url.encodedPath) {
                "/token" -> respond("""{"access_token":"t${++n}","expires_in":300}""", HttpStatusCode.OK, json)
                else -> {
                    bodies += request.headers[HttpHeaders.ContentEncoding] to request.body.toByteArray()
                    batchAnswer().let { (status, body) -> respond(body, status, json) }
                }
            }
        }
        return HttpCentral(HttpClient(engine), { "idem" }, { Instant.parse("2026-09-29T04:30:00Z") }, fakeGzip).apply { identify(device) }
    }

    private val batch = buildJsonObject { put("batch_id", "b1") }

    @Test
    fun aBatchGoesWithADeviceTokenAndTheAcknowledgementIsRead() = runTest {
        val central = central { HttpStatusCode.OK to """{"batch_id":"b1","last_applied_seq":4,"snapshot_version":9,"clock_offset_ms":-120,"outcomes":[],"instructions":[]}""" }

        assertEquals(UploadAnswer.Acknowledged(4, 9, -120), central.upload(batch))
        assertEquals(listOf("POST /token", "POST /v1/sync/devices/dev-1/batches Bearer t1"), calls)
    }

    @Test
    fun theBatchBodyIsGzipAndSaysSo() = runTest {
        val central = central { HttpStatusCode.OK to """{"last_applied_seq":1}""" }
        central.upload(batch)

        val (encoding, body) = bodies.single()
        assertEquals("gzip", encoding)
        assertEquals("GZ:" + batch.toString(), body.decodeToString())
    }

    @Test
    fun theOutcomesInstructionsAndServerTimeOfTheAcknowledgementAreKept() = runTest {
        val central = central {
            HttpStatusCode.OK to """{"batch_id":"b1","last_applied_seq":5,"snapshot_version":9,"server_time":"2026-09-29T04:30:01Z",
                "outcomes":[{"device_seq":4,"event_id":"e4","outcome":"APPLIED"},{"device_seq":5,"event_id":"e5","outcome":"QUARANTINED","reason":"HASH"}],
                "instructions":[{"type":"RESEND_FROM","from_seq":3},{"type":"FLOOR_NOTICE","detail":"0.2.0"}]}"""
        }
        val ack = central.upload(batch) as UploadAnswer.Acknowledged

        assertEquals(listOf(EventOutcome(4, "e4", "APPLIED", null), EventOutcome(5, "e5", "QUARANTINED", "HASH")), ack.outcomes)
        assertEquals(listOf(Instruction("RESEND_FROM", 3, null), Instruction("FLOOR_NOTICE", null, "0.2.0")), ack.instructions)
        assertEquals(Instant.parse("2026-09-29T04:30:01Z"), ack.serverTime)
    }

    @Test
    fun anExpiredTokenIsFetchedAgainOnce() = runTest {
        var first = true
        val central = central {
            if (first) { first = false; HttpStatusCode.Unauthorized to "{}" } else HttpStatusCode.OK to """{"last_applied_seq":1}"""
        }
        central.upload(batch)
        assertEquals("POST /v1/sync/devices/dev-1/batches Bearer t2", calls.last())
    }

    @Test
    fun centralSayingWaitIsNotAnError() = runTest {
        val central = central { HttpStatusCode.TooManyRequests to """{"code":"sync.rate_limited","params":{"retry_after":7}}""" }
        assertEquals(UploadAnswer.RateLimited(7), central.upload(batch))
    }

    @Test
    fun aServerErrorMeansOfflineAndARefusalIsReported() = runTest {
        assertFailsWith<CentralUnreachable> { central { HttpStatusCode.BadGateway to "" }.upload(batch) }
        val refused = assertFailsWith<CentralRefused> { central { HttpStatusCode.Conflict to """{"code":"sync.gap"}""" }.upload(batch) }
        assertEquals(409, refused.status)
    }
}
