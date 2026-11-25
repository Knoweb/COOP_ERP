package lk.coopfed.knoweb.till.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.sale.Facts
import lk.coopfed.knoweb.till.core.snapshot.Catalogue
import lk.coopfed.knoweb.till.core.snapshot.SnapshotDelta
import lk.coopfed.knoweb.till.core.snapshot.SnapshotRow
import lk.coopfed.knoweb.till.core.snapshot.SnapshotState

/**
 * Proves the requirements of TILL-PLAT-03 (FR-SEC-070, T-09):
 * 1. Read Configuration: Retrieve the configured idle time (till.idle_lock) from the kernel configuration snapshot.
 * 2. Idle Timer: An inactivity timer on the till resets on user interaction and triggers after the configured till.idle_lock duration.
 * 3. Lock Screen: When the idle timer triggers, the till session/screen locks, and the operator must enter their PIN to unlock and resume.
 * 4. A colleague can unlock with their PIN, auditing the switch as TILL_OPERATOR_SWITCH.
 */
class TillIdleLockTest {

    private val cashierUser = Samples.operator(
        pinHash = "pin:1234",
        id = "0190f0de-0000-7000-8000-0000000b0001",
        name = "Nimali",
        permissions = listOf(TillPolicy.RECEIPT_ISSUE_PERMISSION),
    )

    private val supervisorUser = Samples.operator(
        pinHash = "pin:5678",
        id = "0190f0de-0000-7000-8000-0000000b0002",
        name = "Kamal",
        permissions = listOf(TillPolicy.RECEIPT_ISSUE_PERMISSION, TillPolicy.SUPERVISOR_PERMISSION),
    )

    private fun configRow(idleLock: String) = SnapshotRow(
        "config",
        "8b51d8b2-6cb1-3a05-9e6b-a2ebcf218f0c",
        null,
        buildJsonObject {
            put("key", "till.idle_lock")
            put("value", idleLock)
        },
    )

    private suspend fun tillWithIdleLock(idleLock: String = "PT1M"): TillFixture =
        TillFixture(
            operators = listOf(cashierUser, supervisorUser),
            extraRows = listOf(configRow(idleLock)),
        ).enrolled()

    private fun TillFixture.cashier() = service.catalogue.operators.single { it.displayName == "Nimali" }
    private fun TillFixture.supervisor() = service.catalogue.operators.single { it.displayName == "Kamal" }

    @Test
    fun idleLockReadsConfiguredDurationFromSnapshotConfig() {
        val basePolicy = TillPolicy()
        assertEquals(2.minutes, basePolicy.idleLock)

        // 1. Snapshot with PT1M (1 minute)
        val state1m = SnapshotState.EMPTY.apply(
            SnapshotDelta.fromJson(Samples.snapshotAnswer(1, listOf(configRow("PT1M")))),
            LocalDate(2026, 10, 1),
        )
        val catalogue1m = Catalogue(state1m)
        assertEquals("PT1M", catalogue1m.config["till.idle_lock"])
        val policy1m = basePolicy.withConfig(catalogue1m.config)
        assertEquals(1.minutes, policy1m.idleLock)

        // 2. Snapshot with PT30S (30 seconds)
        val state30s = SnapshotState.EMPTY.apply(
            SnapshotDelta.fromJson(Samples.snapshotAnswer(2, listOf(configRow("PT30S")))),
            LocalDate(2026, 10, 1),
        )
        val catalogue30s = Catalogue(state30s)
        val policy30s = basePolicy.withConfig(catalogue30s.config)
        assertEquals(30.seconds, policy30s.idleLock)
    }

    @Test
    fun anIdleTillLocksExactlyAtTheConfiguredTime() = runTest {
        // Configured for PT1M (60 seconds)
        val till = tillWithIdleLock("PT1M")
        assertEquals(1.minutes, till.service.idleLockDuration)

        // Cashier signs in and opens a session
        till.service.signIn(till.cashier(), "1234")
        assertFalse(till.service.isLocked)
        till.service.openSession(Money.parse("1000.00"))

        // Time advances 59 seconds: till is NOT locked
        till.clock.advance(59.seconds)
        assertFalse(till.service.checkIdleLock())
        assertFalse(till.service.isLocked)
        assertFalse(till.service.status.value.locked)

        // Time advances 1 more second (reaches 60 seconds): till locks exactly now
        till.clock.advance(1.seconds)
        assertTrue(till.service.checkIdleLock())
        assertTrue(till.service.isLocked)
        assertTrue(till.service.status.value.locked)

        // Performing actions while locked is strictly refused
        val refusal = assertFailsWith<TillRefusal> {
            till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("2000.00"))
        }
        assertEquals("The till is locked: enter PIN to unlock", refusal.message)
    }

    @Test
    fun userInteractionResetsTheIdleTimer() = runTest {
        val till = tillWithIdleLock("PT1M")

        till.service.signIn(till.cashier(), "1234")
        till.service.openSession(Money.parse("1000.00"))

        // After 45 seconds of idle
        till.clock.advance(45.seconds)
        assertFalse(till.service.checkIdleLock())

        // User interacts (e.g. keyboard activity or scanning an item)
        till.service.recordActivity()

        // 45 seconds later (total 90s from start, but 45s since activity): NOT locked
        till.clock.advance(45.seconds)
        assertFalse(till.service.checkIdleLock())
        assertFalse(till.service.isLocked)

        // 15 seconds later (reaches 60s from activity): locks
        till.clock.advance(15.seconds)
        assertTrue(till.service.checkIdleLock())
        assertTrue(till.service.isLocked)
    }

    @Test
    fun aPinIsRequiredToUnlockTheTill() = runTest {
        val till = tillWithIdleLock("PT1M")

        till.service.signIn(till.cashier(), "1234")
        till.service.openSession(Money.parse("1000.00"))

        // Till locks after 60 seconds
        till.clock.advance(60.seconds)
        till.service.lock()
        assertTrue(till.service.isLocked)

        // Wrong PIN is refused; till remains locked
        val wrongPin = assertFailsWith<TillRefusal> { till.service.unlock("9999") }
        assertEquals("Wrong PIN", wrongPin.message)
        assertTrue(till.service.isLocked)

        // Correct PIN unlocks the till
        val unlocked = till.service.unlock("1234")
        assertEquals(till.cashier().userId, unlocked.userId)
        assertFalse(till.service.isLocked)
        assertFalse(till.service.status.value.locked)

        // Normal actions succeed once unlocked
        val receipt = till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("2000.00"))
        assertEquals(Money.parse("1250.00"), receipt.gross)
    }

    @Test
    fun colleagueCanUnlockWithAuditedOperatorSwitch() = runTest {
        val till = tillWithIdleLock("PT1M")

        till.service.signIn(till.cashier(), "1234")
        assertEquals(till.cashier().userId, till.service.operator?.userId)

        // Till locks
        till.clock.advance(60.seconds)
        till.service.lock()
        assertTrue(till.service.isLocked)

        // Colleague (supervisor Kamal) unlocks with his PIN
        val unlockedSupervisor = till.service.unlock(till.supervisor(), "5678", reason = "Colleague resume")
        assertEquals(till.supervisor().userId, unlockedSupervisor.userId)
        assertEquals(till.supervisor().userId, till.service.operator?.userId)
        assertFalse(till.service.isLocked)

        // Operator switch fact was audited to the outbox
        val switchAudit = till.store.pendingOutbox(50).single { it.eventType == Facts.OPERATOR_SWITCH_EVENT }
        val event = Json.parseToJsonElement(switchAudit.json).jsonObject
        assertEquals(till.supervisor().userId, event.getValue("actor_user_id").jsonPrimitive.content)

        val payload = event.getValue("payload").jsonObject
        assertEquals(Facts.OPERATOR_SWITCH_AUDIT, payload.getValue("event_type_code").jsonPrimitive.content)
        assertEquals("Colleague resume", payload.getValue("reason_text").jsonPrimitive.content)
        assertEquals(till.cashier().userId, payload.getValue("before_state").jsonObject.getValue("operator_user_id").jsonPrimitive.content)
        assertEquals(till.supervisor().userId, payload.getValue("after_state").jsonObject.getValue("operator_user_id").jsonPrimitive.content)
    }
}
