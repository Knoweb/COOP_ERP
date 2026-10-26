package lk.coopfed.knoweb.till.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lk.coopfed.knoweb.till.core.model.CashMovementKind
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.receipt.Block
import lk.coopfed.knoweb.till.core.receipt.ReceiptLayouts
import lk.coopfed.knoweb.till.core.receipt.SlipHeader
import lk.coopfed.knoweb.till.core.snapshot.Language

class CashMovementTest {

    @Test
    fun cashMovementsRequireAnOpenSession() = runTest {
        val till = TillFixture().enrolled()
        till.service.signIn(till.service.catalogue.operators.single(), "1234")

        val refusal = assertFailsWith<TillRefusal> {
            till.service.recordCashDrop(Money.parse("1000.00"), "Mid-day safe drop")
        }
        assertEquals("No session is open", refusal.message)
    }

    @Test
    fun cashMovementsRequirePositiveAmountAndNonEmptyReason() = runTest {
        val till = TillFixture().signedInWithSession(float = "2000.00")

        assertFailsWith<TillRefusal> {
            till.service.recordCashDrop(Money.parse("0.00"), "Drop zero")
        }
        assertFailsWith<TillRefusal> {
            till.service.recordCashDrop(Money.parse("-500.00"), "Drop negative")
        }
        assertFailsWith<TillRefusal> {
            till.service.recordCashDrop(Money.parse("1000.00"), "   ")
        }
        assertFailsWith<TillRefusal> {
            till.service.recordPayout(Money.parse("0.00"), "Payout zero")
        }
        assertFailsWith<TillRefusal> {
            till.service.recordPayout(Money.parse("250.00"), "")
        }
    }

    @Test
    fun cashDropAndPayoutArePersistedAndQueuedInOutboxWithDeviceSequence() = runTest {
        val till = TillFixture().signedInWithSession(float = "2000.00")
        val session = till.service.currentSession()!!

        val drop = till.service.recordCashDrop(Money.parse("1500.00"), "Excess cash drop to safe")
        assertEquals(CashMovementKind.DROP, drop.kind)
        assertEquals(Money.parse("1500.00"), drop.amount)
        assertEquals("Excess cash drop to safe", drop.reason)
        assertEquals(session.sessionId, drop.sessionId)
        assertNotNull(drop.deviceSeq)

        val payout = till.service.recordPayout(Money.parse("350.00"), "Cleaning supplies cash payout")
        assertEquals(CashMovementKind.PAYOUT, payout.kind)
        assertEquals(Money.parse("350.00"), payout.amount)
        assertEquals("Cleaning supplies cash payout", payout.reason)
        assertNotNull(payout.deviceSeq)
        assertTrue(payout.deviceSeq!! > drop.deviceSeq!!)

        val movements = till.store.cashMovementsOfSession(session.sessionId)
        assertEquals(2, movements.size)
        assertEquals(drop.movementId, movements[0].movementId)
        assertEquals(payout.movementId, movements[1].movementId)

        val pending = till.store.pendingOutbox(10)
        val dropEvent = pending.first { it.eventType == "cash_movement.recorded.v1" && it.deviceSeq == drop.deviceSeq }
        val dropPayload = Json.parseToJsonElement(dropEvent.json).jsonObject["payload"]!!.jsonObject
        assertEquals(drop.movementId, dropPayload["movement_id"]!!.jsonPrimitive.content)
        assertEquals("DROP", dropPayload["kind"]!!.jsonPrimitive.content)
        assertEquals("1500.00", dropPayload["amount"]!!.jsonPrimitive.content)
        assertEquals("Excess cash drop to safe", dropPayload["reason"]!!.jsonPrimitive.content)

        val payoutEvent = pending.first { it.eventType == "cash_movement.recorded.v1" && it.deviceSeq == payout.deviceSeq }
        val payoutPayload = Json.parseToJsonElement(payoutEvent.json).jsonObject["payload"]!!.jsonObject
        assertEquals(payout.movementId, payoutPayload["movement_id"]!!.jsonPrimitive.content)
        assertEquals("PAYOUT", payoutPayload["kind"]!!.jsonPrimitive.content)
        assertEquals("350.00", payoutPayload["amount"]!!.jsonPrimitive.content)
        assertEquals("Cleaning supplies cash payout", payoutPayload["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun zReportSubtractsDropsAndPayoutsFromExpectedCash() = runTest {
        val till = TillFixture().signedInWithSession(float = "5000.00")

        // 1 sale: Rice (1250) + Dhal (385.50 * 2 = 771) = 2021.00
        till.service.sellForCash(till.basket(TillFixture.RICE, TillFixture.DHAL, TillFixture.DHAL), Money.parse("2021.00"))

        // Drops 2000, Payout 500
        till.service.recordCashDrop(Money.parse("2000.00"), "Bank deposit drop")
        till.service.recordPayout(Money.parse("500.00"), "Emergency fuel")

        till.clock.advance(4.hours)

        // Expected cash = float (5000) + cashSales (2021) - drops (2000) - payouts (500) = 4521.00
        val z = till.service.closeSession(Money.parse("4500.00"))

        assertEquals(Money.parse("2021.00"), z.grossSales)
        assertEquals(Money.parse("2021.00"), z.cashSales)
        assertEquals(Money.parse("2000.00"), z.drops)
        assertEquals(Money.parse("500.00"), z.payouts)
        assertEquals(Money.parse("4521.00"), z.expectedCash)
        assertEquals(Money.parse("-21.00"), z.variance)

        // Verify cash movement is rejected after close
        val refusal = assertFailsWith<TillRefusal> {
            till.service.recordCashDrop(Money.parse("100.00"), "Late drop")
        }
        assertEquals("No session is open", refusal.message)
    }

    @Test
    fun slipsRenderInEnglishSinhalaAndTamil() = runTest {
        val till = TillFixture().signedInWithSession()
        val drop = till.service.recordCashDrop(Money.parse("1500.00"), "Safe drop")
        val header = SlipHeader("Shop Kuliyapitiya", "S01", "Counter 1")

        val enSlip = ReceiptLayouts.cashMovement(drop, header, "Kamal", Language.EN, till.clock.zone)
        assertTrue(enSlip.blocks.any { it is Block.Text && it.text == "CASH DROP" })
        assertTrue(enSlip.blocks.any { it is Block.Row && it.left == "Reason" && it.right == "Safe drop" })
        assertTrue(enSlip.blocks.any { it is Block.Row && it.left == "Cashier signature:" })

        val siSlip = ReceiptLayouts.cashMovement(drop, header, "නිමාලි", Language.SI, till.clock.zone)
        assertTrue(siSlip.blocks.any { it is Block.Text && it.text.contains("මුදල් තැන්පතුව") })
        assertTrue(siSlip.blocks.any { it is Block.Row && it.left == "හේතුව" && it.right == "Safe drop" })
        assertTrue(siSlip.blocks.any { it is Block.Row && it.left == "අයකැමි අත්සන:" })

        val taSlip = ReceiptLayouts.cashMovement(drop, header, "கலா", Language.TA, till.clock.zone)
        assertTrue(taSlip.blocks.any { it is Block.Text && it.text.contains("பண வைப்பு") })
        assertTrue(taSlip.blocks.any { it is Block.Row && it.left == "காரணம்" && it.right == "Safe drop" })
        assertTrue(taSlip.blocks.any { it is Block.Row && it.left == "காசாளர் கையொப்பம்:" })
    }
}
