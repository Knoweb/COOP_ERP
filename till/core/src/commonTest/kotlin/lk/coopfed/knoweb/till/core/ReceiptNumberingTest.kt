package lk.coopfed.knoweb.till.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.sale.ContentHash
import lk.coopfed.knoweb.till.core.sale.TenderTooSmall

class ReceiptNumberingTest {

    @Test
    fun receiptsAreNumberedFromTheTillPositionsSeriesOneAfterAnother() = runTest {
        val till = TillFixture()
        till.central.enrolAnswer = Samples.enrolment(nextNumber = 41)
        till.signedInWithSession()

        val first = till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1500"))
        val second = till.service.sellForCash(till.basket(TillFixture.DHAL, TillFixture.DHAL), Money.parse("1000"))

        assertEquals(41, first.number)
        assertEquals("S01-T2-41", first.numberDisplay)
        assertEquals(42, second.number)
        assertEquals("S01-T2-42", second.numberDisplay)
    }

    @Test
    fun theDeviceSequenceIsDenseAcrossEveryKindOfFact() = runTest {
        val till = TillFixture().signedInWithSession()
        till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250"))
        till.service.closeSession(Money.parse("3250"))

        val facts = till.store.pendingOutbox(10)
        assertEquals(listOf(1L, 2L, 3L), facts.map { it.deviceSeq })
        assertEquals(listOf("till_session.opened.v1", "receipt.issued.v1", "till_session.closed.v1"), facts.map { it.eventType })
    }

    @Test
    fun enrollingAgainNeverReusesANumberTheTillAlreadyIssued() = runTest {
        val till = TillFixture()
        till.central.enrolAnswer = Samples.enrolment(nextSeq = 1, nextNumber = 1)
        till.signedInWithSession()
        till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250"))
        till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250"))

        // Central has not seen those receipts yet and still says 1.
        till.service.enrol("http://localhost:8080", "0190f0de-0000-7000-8000-0000000c0001", "CODE2", "DESKTOP-TRIAL-S01")
        val next = till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250"))

        assertEquals(3, next.number)
        assertEquals(4, next.deviceSeq)
    }

    @Test
    fun aPowerCutWhileSellingLeavesNoNumberUsedAndTheBasketIntact() = runTest {
        val till = TillFixture().signedInWithSession()
        val basket = till.basket(TillFixture.RICE)
        till.store.failNextCommit = true

        assertFailsWith<IllegalStateException> { till.service.sellForCash(basket, Money.parse("1250")) }
        assertEquals(1, basket.lines.size)
        val receipt = till.service.sellForCash(basket, Money.parse("1250"))

        assertEquals(1, receipt.number)
        assertEquals(2, receipt.deviceSeq)
    }

    @Test
    fun cashLessThanTheTotalIsRefusedAndNothingIsNumbered() = runTest {
        val till = TillFixture().signedInWithSession()
        assertFailsWith<TenderTooSmall> { till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1000")) }
        assertEquals(1, till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250")).number)
    }

    @Test
    fun theReceiptBundleCarriesItsContentHashByTheKernelRule() = runTest {
        val till = TillFixture().signedInWithSession()
        val receipt = till.service.sellForCash(till.basket(TillFixture.RICE, TillFixture.DHAL), Money.parse("2000"))
        val event = Json.parseToJsonElement(till.store.pendingOutbox(10).single { it.eventType == "receipt.issued.v1" }.json).jsonObject
        val payload = event.getValue("payload").jsonObject

        assertEquals(receipt.contentHash, event.getValue("content_hash").jsonPrimitive.content)
        assertEquals(receipt.contentHash, ContentHash.of(payload.getValue("document").jsonObject, payload.getValue("lines") as kotlinx.serialization.json.JsonArray))
        assertEquals(2, payload.getValue("document").jsonObject.getValue("device_seq").jsonPrimitive.long)
        assertEquals(Money.parse("364.50"), receipt.change)
    }
}
