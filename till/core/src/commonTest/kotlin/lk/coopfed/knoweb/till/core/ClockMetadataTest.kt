package lk.coopfed.knoweb.till.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import lk.coopfed.knoweb.till.core.model.Anomaly
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.sale.Facts

/**
 * Tests explicit event-time clock metadata, atomicity of clock state capture,
 * raw local-time evidence preservation, and legacy compatibility (TILL-DATA-10, K-13).
 */
class ClockMetadataTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun offlineEvent_marksOfflineTimedTrue_andOmitsClockOffset() = runTest {
        val till = TillFixture().enrolled()
        till.service.signIn(till.service.catalogue.operators.single(), "1234")

        // Wall clock is 04:30:00 UTC (10:00:00 Asia/Colombo)
        till.clock.setWallClock(Instant.parse("2026-09-29T04:30:00Z"))
        till.service.openSession(Money.parse("1000"))

        val entry = till.store.pendingOutbox(10).single { it.eventType == "till_session.opened.v1" }
        val eventObj = json.parseToJsonElement(entry.json).jsonObject

        assertEquals("true", eventObj["offline_timed"]?.jsonPrimitive?.content)
        assertNull(eventObj["clock_offset_ms"])
        // Both occurred_at and occurred_local correspond to the unadjusted raw device time
        assertEquals("2026-09-29T04:30:00Z", eventObj["occurred_at"]?.jsonPrimitive?.content)
        assertEquals("2026-09-29T10:00:00", eventObj["occurred_local"]?.jsonPrimitive?.content)
    }

    @Test
    fun onlineEvent_stampsClockOffset_andPreservesRawLocalTimeEvidence() = runTest {
        val till = TillFixture().enrolled()
        till.service.signIn(till.service.catalogue.operators.single(), "1234")

        // Set wall clock to 04:30:00 UTC (10:00:00 Asia/Colombo)
        till.clock.setWallClock(Instant.parse("2026-09-29T04:30:00Z"))
        // Heartbeat informs till of +120_000ms (+2 min) offset: real server time is 04:32:00
        heartbeatSays(till, offsetMs = 120_000)

        till.service.openSession(Money.parse("1000"))
        val receipt = till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250"))

        val entry = till.store.pendingOutbox(10).single { it.deviceSeq == receipt.deviceSeq }
        val eventObj = json.parseToJsonElement(entry.json).jsonObject

        // 1. clock_offset_ms represents the offset actually applied at event time
        assertEquals(120_000L, eventObj["clock_offset_ms"]?.jsonPrimitive?.longOrNull)
        // 2. offline_timed is not true
        assertNull(eventObj["offline_timed"])
        // 3. occurred_at reflects wall clock + offset (04:32:00Z)
        assertEquals("2026-09-29T04:32:00Z", eventObj["occurred_at"]?.jsonPrimitive?.content)
        // 4. occurred_local preserves RAW device wall-clock reading (10:00:00, not 10:02:00!)
        assertEquals("2026-09-29T10:00:00", eventObj["occurred_local"]?.jsonPrimitive?.content)
    }

    @Test
    fun clockJumpDropsOffset_andSubsequentEventIsMarkedOfflineTimed() = runTest {
        val till = TillFixture().enrolled()
        till.service.signIn(till.service.catalogue.operators.single(), "1234")
        heartbeatSays(till, offsetMs = 120_000)

        // PC clock moves backwards: offset is dropped
        till.clock.setWallClock(Instant.parse("2026-09-29T04:00:00Z"))
        till.service.openSession(Money.parse("0"))

        val entry = till.store.pendingOutbox(10).single { it.eventType == "till_session.opened.v1" }
        val eventObj = json.parseToJsonElement(entry.json).jsonObject

        assertEquals(true, eventObj["offline_timed"]?.jsonPrimitive?.booleanOrNull)
        assertNull(eventObj["clock_offset_ms"])
        assertEquals("2026-09-29T04:00:00Z", eventObj["occurred_at"]?.jsonPrimitive?.content)
        assertEquals("CLOCK_BACKWARDS", till.service.problems().single { it.kind == Anomaly.CLOCK }.reason)
    }

    @Test
    fun allEventCreationCallSites_consistentlyCaptureClockMetadata() = runTest {
        val cashierUser = Samples.operator("pin:1234", id = "0190f0de-0000-7000-8000-0000000b0001", name = "Nimal")
        val supervisorUser = Samples.operator(
            "pin:9999", id = "0190f0de-0000-7000-8000-0000000b0002", name = "Kamal",
            permissions = listOf(TillPolicy.SUPERVISOR_PERMISSION),
        )
        val till = TillFixture(operators = listOf(cashierUser, supervisorUser)).enrolled()
        val cashier = till.service.catalogue.operators.single { it.displayName == "Nimal" }
        val supervisor = till.service.catalogue.operators.single { it.displayName == "Kamal" }

        till.clock.setWallClock(Instant.parse("2026-09-29T04:30:00Z"))
        heartbeatSays(till, offsetMs = 60_000) // +1 min offset
        till.service.signIn(cashier, "1234")

        // 1. Session opened event
        val session = till.service.openSession(Money.parse("1000"))
        val openEvent = till.store.pendingOutbox(50).single { it.eventType == "till_session.opened.v1" }
        val openObj = json.parseToJsonElement(openEvent.json).jsonObject
        assertEquals(60_000L, openObj["clock_offset_ms"]?.jsonPrimitive?.longOrNull)

        // 2. Receipt issued event
        val receipt = till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250"))
        val receiptEntry = till.store.pendingOutbox(50).single { it.deviceSeq == receipt.deviceSeq }
        val receiptObj = json.parseToJsonElement(receiptEntry.json).jsonObject
        assertEquals(60_000L, receiptObj["clock_offset_ms"]?.jsonPrimitive?.longOrNull)

        // 3. Session closed event
        till.service.closeSession(Money.parse("2250"))
        val closeEvent = till.store.pendingOutbox(50).single { it.eventType == "till_session.closed.v1" }
        val closeObj = json.parseToJsonElement(closeEvent.json).jsonObject
        assertEquals(60_000L, closeObj["clock_offset_ms"]?.jsonPrimitive?.longOrNull)

        // 4. Operator switch audit event
        till.service.switchOperator(supervisor, "9999", "Shift change")
        val switchAudit = till.store.pendingOutbox(50).single { it.eventType == Facts.OPERATOR_SWITCH_EVENT }
        val switchObj = json.parseToJsonElement(switchAudit.json).jsonObject
        assertEquals(60_000L, switchObj["clock_offset_ms"]?.jsonPrimitive?.longOrNull)
        assertEquals("2026-09-29T10:00:00", switchObj["occurred_local"]?.jsonPrimitive?.content)

        // 5. Business date correction audit event
        till.clock.advance(1.hours)
        till.service.correctBusinessDate(LocalDate(2026, 9, 28), supervisor, "9999")
        val dateAudit = till.store.pendingOutbox(50).single { it.eventType == Facts.BUSINESS_DATE_CORRECTED_EVENT }
        val dateObj = json.parseToJsonElement(dateAudit.json).jsonObject
        assertEquals(60_000L, dateObj["clock_offset_ms"]?.jsonPrimitive?.longOrNull)

        // 6. PIN lockout audit event (TWK-10: 5 wrong PINs lock out the till)
        repeat(5) {
            try {
                till.service.switchOperator(cashier, "wrong-pin")
            } catch (_: TillRefusal) {}
        }
        val lockoutAudit = till.store.pendingOutbox(50).single { it.eventType == Facts.PIN_LOCKOUT_EVENT }
        val lockoutObj = json.parseToJsonElement(lockoutAudit.json).jsonObject
        assertEquals(60_000L, lockoutObj["clock_offset_ms"]?.jsonPrimitive?.longOrNull)
    }

    private suspend fun heartbeatSays(till: TillFixture, offsetMs: Long) {
        till.central.clockOffsetMs = null
        till.central.heartbeatAnswer = buildJsonObject {
            put("snapshot_version", 7)
            put("clock_offset_ms", offsetMs)
            put("server_time", "2026-09-29T04:32:00Z")
        }
        till.service.syncOnce()
    }
}
