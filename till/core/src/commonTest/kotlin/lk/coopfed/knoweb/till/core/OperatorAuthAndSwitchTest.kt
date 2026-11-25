package lk.coopfed.knoweb.till.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.sale.Facts

/**
 * Proves the requirements of TILL-PLAT-02:
 * 1. A seeded cashier signs in using their PIN.
 * 2. The cashier switches with a colleague (verifying the operator switch audit event).
 * 3. The cashier is explicitly refused when attempting to perform a supervisor action.
 * 4. Actions strictly check against the user's signed permissions from the snapshot.
 */
class OperatorAuthAndSwitchTest {

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

    private val unprivilegedUser = Samples.operator(
        pinHash = "pin:9999",
        id = "0190f0de-0000-7000-8000-0000000b0003",
        name = "Unprivileged",
        permissions = emptyList(),
    )

    private suspend fun tillWithOperators(): TillFixture =
        TillFixture(operators = listOf(cashierUser, supervisorUser, unprivilegedUser)).enrolled()

    private fun TillFixture.cashier() = service.catalogue.operators.single { it.displayName == "Nimali" }
    private fun TillFixture.supervisor() = service.catalogue.operators.single { it.displayName == "Kamal" }
    private fun TillFixture.unprivileged() = service.catalogue.operators.single { it.displayName == "Unprivileged" }

    @Test
    fun aSeededCashierSignsInUsingTheirPin() = runTest {
        val till = tillWithOperators()

        // Wrong PIN is refused
        val wrongPin = assertFailsWith<TillRefusal> { till.service.signIn(till.cashier(), "9999") }
        assertEquals("Wrong PIN", wrongPin.message)

        // Seeded cashier signs in with PIN 1234
        till.service.signIn(till.cashier(), "1234")
        assertEquals(till.cashier().userId, till.service.operator?.userId)
        assertEquals("Nimali", till.service.operator?.displayName)

        // Cashier has pos.receipt.issue and can open a session and sell
        val session = till.service.openSession(Money.parse("1000.00"))
        assertEquals(till.cashier().userId, session.operatorUserId)

        val receipt = till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("2000.00"))
        assertEquals(till.cashier().userId, receipt.operatorUserId)
        assertEquals(Money.parse("1250.00"), receipt.gross)
    }

    @Test
    fun theCashierSwitchesWithAColleagueVerifyingTheOperatorSwitchAudit() = runTest {
        val till = tillWithOperators()

        // 1. Cashier signs in
        till.service.signIn(till.cashier(), "1234")
        assertEquals("Nimali", till.service.operator?.displayName)

        // Cannot switch to the same operator
        val sameRefusal = assertFailsWith<TillRefusal> {
            till.service.switchOperator(till.cashier(), "1234")
        }
        assertEquals("Nimali is already signed in", sameRefusal.message)

        // Wrong PIN for colleague is refused
        assertFailsWith<TillRefusal> {
            till.service.switchOperator(till.supervisor(), "0000")
        }

        // 2. Cashier switches with colleague using colleague's PIN
        val switched = till.service.switchOperator(till.supervisor(), "5678", reason = "Shift change")
        assertEquals(till.supervisor().userId, switched.userId)
        assertEquals(till.supervisor().userId, till.service.operator?.userId)
        assertEquals("Kamal", till.service.operator?.displayName)

        // 3. Verify operator switch audit event was recorded to outbox
        val switchAudit = till.store.pendingOutbox(50).single { it.eventType == Facts.OPERATOR_SWITCH_EVENT }
        val event = Json.parseToJsonElement(switchAudit.json).jsonObject
        assertEquals(till.supervisor().userId, event.getValue("actor_user_id").jsonPrimitive.content)

        val payload = event.getValue("payload").jsonObject
        assertEquals(Facts.OPERATOR_SWITCH_AUDIT, payload.getValue("event_type_code").jsonPrimitive.content)
        assertEquals("till_position", payload.getValue("subject_table").jsonPrimitive.content)
        assertEquals("OPERATOR_SWITCH", payload.getValue("reason_code").jsonPrimitive.content)
        assertEquals("Shift change", payload.getValue("reason_text").jsonPrimitive.content)
        assertEquals(till.cashier().userId, payload.getValue("before_state").jsonObject.getValue("operator_user_id").jsonPrimitive.content)
        assertEquals(till.supervisor().userId, payload.getValue("after_state").jsonObject.getValue("operator_user_id").jsonPrimitive.content)
    }

    @Test
    fun theCashierIsExplicitlyRefusedWhenAttemptingToPerformASupervisorAction() = runTest {
        val till = tillWithOperators()

        // PC clock moves forward, opening a business date in the future
        till.service.signIn(till.cashier(), "1234")
        till.clock.setWallClock(kotlin.time.Instant.parse("2027-09-29T04:30:00Z"))
        val session = till.service.openSession(Money.parse("0"))
        assertEquals(LocalDate(2027, 9, 29), session.businessDate)
        till.service.closeSession(Money.parse("0"))

        // Clock is restored; supervisor wants to move the business date back
        till.clock.setWallClock(kotlin.time.Instant.parse("2026-09-29T05:00:00Z"))

        // Cashier attempts supervisor action (correctBusinessDate) - explicitly refused
        val refusal = assertFailsWith<TillRefusal> {
            till.service.correctBusinessDate(LocalDate(2026, 9, 29), till.cashier(), "1234")
        }
        assertTrue(refusal.message!!.contains("may not correct the business date (${TillPolicy.SUPERVISOR_PERMISSION})"))

        // Also refused when calling without explicit supervisor parameter (acting as current operator)
        val defaultRefusal = assertFailsWith<TillRefusal> {
            till.service.correctBusinessDate(LocalDate(2026, 9, 29), pin = "1234")
        }
        assertTrue(defaultRefusal.message!!.contains("may not correct the business date (${TillPolicy.SUPERVISOR_PERMISSION})"))

        // But the supervisor with SUPERVISOR_PERMISSION succeeds
        till.service.correctBusinessDate(LocalDate(2026, 9, 29), till.supervisor(), "5678")
        assertEquals(LocalDate(2026, 9, 29), till.service.currentBusinessDate())
    }

    @Test
    fun anOperatorWithoutReceiptIssuePermissionIsRefusedFromIssuingReceipts() = runTest {
        val till = tillWithOperators()

        // Unprivileged operator signs in
        till.service.signIn(till.unprivileged(), "9999")
        assertEquals("Unprivileged", till.service.operator?.displayName)

        // Attempting to open session without pos.receipt.issue is refused
        val openRefusal = assertFailsWith<TillRefusal> {
            till.service.openSession(Money.parse("1000.00"))
        }
        assertTrue(openRefusal.message!!.contains("may not open a session (${TillPolicy.RECEIPT_ISSUE_PERMISSION})"))
    }
}
