package lk.coopfed.knoweb.till.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lk.coopfed.knoweb.till.core.model.Anomaly
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.port.Settings
import lk.coopfed.knoweb.till.core.sale.Facts

/**
 * What the till trusts and what survives a restart or a power cut (decision
 * 2026-10-06-wave2-till-trust-and-durability (2), (4), (5), (6); TWK-02, 03, 04, 06, 07, 10).
 */
class TrustAndDurabilityTest {

    // ---- https (TWK-02, decision D-1) ----

    @Test
    fun aPlainHttpServerIsRefusedExceptOnThisPc() = runTest {
        val till = TillFixture()
        till.service.start()

        val refusal = assertFailsWith<TillRefusal> { till.service.enrol("http://central.example.lk", Samples.DEVICE, "CODE", "S") }
        assertTrue(refusal.message!!.startsWith("The server address must start with https://"))
        assertFailsWith<TillRefusal> { till.service.enrol("ftp://central.example.lk", Samples.DEVICE, "CODE", "S") }
        assertFalse(till.service.isEnrolled)

        for (local in listOf("http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080")) {
            till.central.enrolAnswer = Samples.enrolment(tokenEndpoint = local.replace(":8080", ":8085") + "/token")
            till.service.enrol(local, Samples.DEVICE, "CODE", "S")
        }
        till.central.enrolAnswer = Samples.enrolment(tokenEndpoint = "http://till.localhost/token")
        till.service.enrol("http://till.localhost", Samples.DEVICE, "CODE", "S")
        till.central.enrolAnswer = Samples.enrolment(tokenEndpoint = "https://central.example.lk/realms/coop/token")
        assertEquals("https://central.example.lk", till.service.enrol("https://central.example.lk/", Samples.DEVICE, "CODE", "S").serverUrl)
    }

    @Test
    fun anEnrolmentAnswerThatSendsTheSecretToAnotherHostIsRefused() = runTest {
        val till = TillFixture()
        till.service.start()
        till.central.enrolAnswer = Samples.enrolment(tokenEndpoint = "https://idp.example.net/token")

        val refusal = assertFailsWith<TillRefusal> { till.service.enrol("https://central.example.lk", Samples.DEVICE, "CODE", "S") }

        assertTrue(refusal.message!!.contains("another host"))
        assertFalse(till.service.isEnrolled)
        assertNull(till.store.setting(Settings.DEVICE))
    }

    @Test
    fun anIdentityProviderSetOnThisPcIsTheOperatorsChoice() = runTest {
        val till = TillFixture(tokenEndpointSetLocally = true)
        till.service.start()
        till.central.enrolAnswer = Samples.enrolment(tokenEndpoint = "http://keycloak:8080/token")

        till.service.enrol("https://central.example.lk", Samples.DEVICE, "CODE", "S")

        assertTrue(till.service.isEnrolled)
    }

    @Test
    fun anIdentityEnrolledOverPlainHttpBeforeTheRuleIsNotUsedAtStart() = runTest {
        val till = TillFixture().enrolled()
        val stored = till.store.setting(Settings.DEVICE)!!
        till.store.putSetting(Settings.DEVICE, stored.replace("http://localhost:8080", "http://central.example.lk"))

        val service = till.restart()

        assertFalse(service.isEnrolled)
        assertTrue(service.status.value.banners.single().contains("enrol this till again over https"))
    }

    // ---- the PIN (TWK-03, TWK-10) ----

    @Test
    fun theLockOutSurvivesARestartAndIsAnAuditFactForCentral() = runTest {
        val till = TillFixture().enrolled()
        repeat(4) { assertFailsWith<TillRefusal> { till.service.signIn(till.service.catalogue.operators.single(), "0000") } }

        till.restart()
        val fifth = assertFailsWith<TillRefusal> { till.service.signIn(till.service.catalogue.operators.single(), "0000") }
        assertTrue(fifth.message!!.startsWith("Too many wrong PINs"))

        till.restart()
        val locked = assertFailsWith<TillRefusal> { till.service.signIn(till.service.catalogue.operators.single(), "1234") }
        assertTrue(locked.message!!.startsWith("Too many wrong PINs"))

        val audit = till.store.pendingOutbox(10).single { it.eventType == Facts.PIN_LOCKOUT_EVENT }
        val payload = Json.parseToJsonElement(audit.json).jsonObject.getValue("payload").jsonObject
        assertEquals("TILL_PIN_LOCKOUT", payload.getValue("event_type_code").jsonPrimitive.content)
        // No PIN, and nothing about which PINs were tried.
        assertEquals(
            setOf("event_type_code", "subject_table", "subject_id", "till_position_id", "reason_code", "after_state"),
            payload.keys,
        )

        till.clock.advance(16.minutes)
        till.service.signIn(till.service.catalogue.operators.single(), "1234")
        assertEquals("Nimali", till.service.operator?.displayName)
    }

    @Test
    fun signingInAsOneselfBetweenGuessesDoesNotResetTheCountAgainstAnotherOperator() = runTest {
        val till = twoOperators()
        repeat(4) { assertFailsWith<TillRefusal> { till.service.signIn(till.kamal(), "0000") } }
        // The cashier signs in with her own PIN and out again (TILLM6-02).
        till.service.signIn(till.nimali(), "1234")
        till.service.signOut()

        val fifth = assertFailsWith<TillRefusal> { till.service.signIn(till.kamal(), "0001") }

        assertTrue(fifth.message!!.startsWith("Too many wrong PINs"))
        assertTrue(till.store.pendingOutbox(10).any { it.eventType == Facts.PIN_LOCKOUT_EVENT })
        val locked = assertFailsWith<TillRefusal> { till.service.signIn(till.nimali(), "1234") }
        assertTrue(locked.message!!.startsWith("Too many wrong PINs"), "the lock-out holds the whole till")
    }

    @Test
    fun aCorrectPinClearsOnlyTheCountOfTheOperatorWhoSignedIn() = runTest {
        val till = twoOperators()
        repeat(4) { assertFailsWith<TillRefusal> { till.service.signIn(till.kamal(), "0000") } }
        till.service.signIn(till.kamal(), "9999")
        till.service.signOut()

        repeat(4) { assertEquals("Wrong PIN", assertFailsWith<TillRefusal> { till.service.signIn(till.kamal(), "0000") }.message) }

        assertNull(till.store.setting(Settings.PIN_LOCKED_UNTIL))
        assertTrue(till.store.pendingOutbox(10).none { it.eventType == Facts.PIN_LOCKOUT_EVENT })
    }

    @Test
    fun movingThePcClockForwardDoesNotEndTheLockOut() = runTest {
        val till = TillFixture().enrolled()
        val nimali = till.service.catalogue.operators.single()
        repeat(5) { assertFailsWith<TillRefusal> { till.service.signIn(nimali, "0000") } }

        // The PC's clock is set 16 minutes ahead; no real time passed (TILLM6-07).
        till.clock.setWallClock(till.clock.instant + 16.minutes)
        assertTrue(assertFailsWith<TillRefusal> { till.service.signIn(nimali, "1234") }.message!!.startsWith("Too many wrong PINs"))
        till.restart()
        assertTrue(assertFailsWith<TillRefusal> { till.service.signIn(nimali, "1234") }.message!!.startsWith("Too many wrong PINs"))

        till.clock.advance(10.minutes)
        assertTrue(assertFailsWith<TillRefusal> { till.service.signIn(nimali, "1234") }.message!!.startsWith("Too many wrong PINs"))
        till.clock.advance(6.minutes)
        till.service.signIn(nimali, "1234")
        assertEquals("Nimali", till.service.operator?.displayName)
        assertNull(till.store.setting(Settings.PIN_LOCK_REMAINING_MS))
    }

    @Test
    fun aPinRecordTheTillCannotReadSaysAskTheOfficeAndDoesNotCount() = runTest {
        val till = TillFixture(operators = listOf(Samples.operator("\$argon2id\$garbled"))).enrolled()
        val nimali = till.service.catalogue.operators.single()

        repeat(6) {
            val refusal = assertFailsWith<TillRefusal> { till.service.signIn(nimali, "1234") }
            assertEquals("Nimali's PIN record cannot be read; ask the office", refusal.message)
        }
        assertNull(till.store.setting(Settings.PIN_LOCKED_UNTIL))
        assertTrue(till.store.pendingOutbox(10).none { it.eventType == Facts.PIN_LOCKOUT_EVENT })
    }

    // ---- the trial cashier (TWK-04) ----

    @Test
    fun theTrialCashierIsOffOnATillThatDidNotOptIn() = runTest {
        val till = TillFixture(operators = emptyList()).enrolled()

        assertFalse(till.service.trialCashierOffered)
        assertFailsWith<TillRefusal> { till.service.signInTrialCashier() }
    }

    @Test
    fun theTrialCashierIsNeverOfferedOnceASnapshotHasCarriedAnOperator() = runTest {
        val till = TillFixture(operators = emptyList(), trialCashier = true).enrolled()
        assertTrue(till.service.trialCashierOffered)
        till.service.signInTrialCashier()

        till.central.snapshotAnswer = Samples.snapshotAnswer(8, till.rows + Samples.operator("pin:1234"))
        till.service.refreshSnapshot()
        // Every operator is tombstoned afterwards: the operator table is empty again.
        till.central.snapshotAnswer = Samples.snapshotAnswer(9, till.rows)
        till.service.refreshSnapshot()
        assertTrue(till.service.catalogue.operators.isEmpty())

        assertFalse(till.service.trialCashierOffered)
        assertFailsWith<TillRefusal> { till.service.signInTrialCashier() }
        assertFalse(till.restart().trialCashierOffered)
    }

    // ---- durability (TWK-06) ----

    @Test
    fun countersBelowANumberAlreadyUsedAreRepairedAtStart() = runTest {
        val till = TillFixture().signedInWithSession()
        repeat(2) { till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250")) }
        // As if the counters' last write was lost and the receipts' was not.
        till.store.putSetting(Settings.NEXT_RECEIPT_NUMBER, "2")
        till.store.putSetting(Settings.NEXT_DEVICE_SEQ, "2")

        till.restart()
        till.service.signIn(till.service.catalogue.operators.single(), "1234")
        val next = till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250"))

        assertEquals(3, next.number)
        assertEquals(4, next.deviceSeq)
        assertEquals(2, till.service.problems().count { it.kind == Anomaly.COUNTER_REPAIRED })
    }

    // ---- the clock (TWK-07, decision D-4) ----

    @Test
    fun theOffsetIsTakenFromEveryHeartbeat() = runTest {
        val till = TillFixture().enrolled()
        till.service.signIn(till.service.catalogue.operators.single(), "1234")
        heartbeatSays(till, offsetMs = 120_000)

        val session = till.service.openSession(Money.parse("0"))

        assertEquals("120000", till.store.setting(Settings.CLOCK_OFFSET_MS))
        assertEquals(Instant.parse("2026-09-29T04:32:00Z").epochSeconds, session.openedAt)
    }

    @Test
    fun theOffsetIsDroppedWhenThePcClockGoesBack() = runTest {
        val till = TillFixture().enrolled()
        till.service.signIn(till.service.catalogue.operators.single(), "1234")
        heartbeatSays(till, offsetMs = 120_000)

        till.clock.setWallClock(Instant.parse("2026-09-29T04:00:00Z"))
        val session = till.service.openSession(Money.parse("0"))

        assertEquals(Instant.parse("2026-09-29T04:00:00Z").epochSeconds, session.openedAt)
        assertEquals("CLOCK_BACKWARDS", till.service.problems().single { it.kind == Anomaly.CLOCK }.reason)
    }

    @Test
    fun theOffsetIsDroppedWhenThePcClockJumpsAheadOfRealTime() = runTest {
        val till = TillFixture().enrolled()
        till.service.signIn(till.service.catalogue.operators.single(), "1234")
        heartbeatSays(till, offsetMs = 120_000)

        till.clock.advance(30.minutes)
        till.clock.setWallClock(till.clock.now() + 3.hours)
        val session = till.service.openSession(Money.parse("0"))

        assertEquals(Instant.parse("2026-09-29T08:00:00Z").epochSeconds, session.openedAt)
        assertEquals("CLOCK_JUMP", till.service.problems().single { it.kind == Anomaly.CLOCK }.reason)
    }

    @Test
    fun theOffsetIsKeptWhileTimeReallyPasses() = runTest {
        val till = TillFixture().enrolled()
        till.service.signIn(till.service.catalogue.operators.single(), "1234")
        heartbeatSays(till, offsetMs = 120_000)

        till.clock.advance(2.hours)
        val session = till.service.openSession(Money.parse("0"))

        assertEquals(Instant.parse("2026-09-29T06:32:00Z").epochSeconds, session.openedAt)
        assertTrue(till.service.problems().none { it.kind == Anomaly.CLOCK })
    }

    @Test
    fun anOffsetOverSevenDaysIsRefusedAndThePcClockKept() = runTest {
        val till = TillFixture().enrolled()
        till.service.signIn(till.service.catalogue.operators.single(), "1234")
        heartbeatSays(till, offsetMs = 8.days.inWholeMilliseconds)

        val session = till.service.openSession(Money.parse("0"))

        assertNull(till.store.setting(Settings.CLOCK_OFFSET_MS))
        assertEquals(Instant.parse("2026-09-29T04:30:00Z").epochSeconds, session.openedAt)
        assertEquals("OFFSET_TOO_LARGE", till.service.problems().single { it.kind == Anomaly.CLOCK }.reason)
    }

    // ---- the business date (decision D-4, CR-32-1 item 4) ----

    private val supervisor = Samples.operator(
        "pin:9999", id = "0190f0de-0000-7000-8000-0000000b0002", name = "Kamal", permissions = listOf(TillPolicy.SUPERVISOR_PERMISSION),
    )

    private suspend fun twoOperators(): TillFixture = TillFixture(operators = listOf(Samples.operator("pin:1234"), supervisor)).enrolled()

    private fun TillFixture.nimali() = service.catalogue.operators.single { it.displayName == "Nimali" }
    private fun TillFixture.kamal() = service.catalogue.operators.single { it.displayName == "Kamal" }

    @Test
    fun aSupervisorMovesTheBusinessDateBackWhenNoSessionIsOpen() = runTest {
        val till = twoOperators()
        till.service.signIn(till.nimali(), "1234")
        // The PC's clock was a year ahead when the day was opened.
        till.clock.setWallClock(Instant.parse("2027-09-29T04:30:00Z"))
        val wrong = till.service.openSession(Money.parse("0"))
        assertEquals(LocalDate(2027, 9, 29), wrong.businessDate)
        till.service.closeSession(Money.parse("0"))
        till.clock.setWallClock(Instant.parse("2026-09-29T05:00:00Z"))

        // Nothing moves it back by itself.
        assertEquals(LocalDate(2027, 9, 29), till.service.openSession(Money.parse("0")).businessDate)
        assertFailsWith<TillRefusal>("not with a session open") {
            till.service.correctBusinessDate(LocalDate(2026, 9, 29), till.kamal(), "9999")
        }
        till.service.closeSession(Money.parse("0"))

        assertFailsWith<TillRefusal>("not by an operator without the permission") {
            till.service.correctBusinessDate(LocalDate(2026, 9, 29), till.nimali(), "1234")
        }
        assertEquals("Wrong PIN", assertFailsWith<TillRefusal> { till.service.correctBusinessDate(LocalDate(2026, 9, 29), till.kamal(), "0000") }.message)
        till.service.correctBusinessDate(LocalDate(2026, 9, 29), till.kamal(), "9999")

        // Central learns who moved it, from what to what (TILLM6-05).
        val audit = till.store.pendingOutbox(50).single { it.eventType == Facts.BUSINESS_DATE_CORRECTED_EVENT }
        val event = Json.parseToJsonElement(audit.json).jsonObject
        assertEquals(till.kamal().userId, event.getValue("actor_user_id").jsonPrimitive.content)
        val payload = event.getValue("payload").jsonObject
        assertEquals("TILL_BUSINESS_DATE_CORRECTED", payload.getValue("event_type_code").jsonPrimitive.content)
        assertEquals("2027-09-29", payload.getValue("before_state").jsonObject.getValue("business_date").jsonPrimitive.content)
        assertEquals("2026-09-29", payload.getValue("after_state").jsonObject.getValue("business_date").jsonPrimitive.content)
        assertEquals(
            setOf("event_type_code", "subject_table", "subject_id", "till_position_id", "reason_code", "before_state", "after_state"),
            payload.keys,
            "no PIN",
        )

        val next = till.service.openSession(Money.parse("0"))
        assertEquals(LocalDate(2026, 9, 29), next.businessDate)
        assertEquals(LocalDate(2027, 9, 29), till.service.zReport(wrong.sessionId)!!.session.businessDate, "issued sessions keep their date")
    }

    @Test
    fun aBusinessDateMoreThanADayAfterCentralsIsRefusedWhenTheTillSyncedRecently() = runTest {
        val till = TillFixture().enrolled()
        till.service.signIn(till.service.catalogue.operators.single(), "1234")
        till.central.heartbeatAnswer = buildJsonObject {
            put("snapshot_version", 7)
            put("server_time", "2026-09-29T04:30:00Z")
        }
        till.service.syncOnce()

        till.clock.setWallClock(Instant.parse("2026-10-02T04:30:00Z"))
        val refusal = assertFailsWith<TillRefusal> { till.service.openSession(Money.parse("0")) }
        assertTrue(refusal.message!!.startsWith("The business date 2026-10-02 is more than a day after central's date (2026-09-29)"))

        till.clock.setWallClock(Instant.parse("2026-09-30T04:30:00Z"))
        assertEquals(LocalDate(2026, 9, 30), till.service.openSession(Money.parse("0")).businessDate)
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
