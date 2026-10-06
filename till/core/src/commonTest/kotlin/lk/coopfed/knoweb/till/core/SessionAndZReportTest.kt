package lk.coopfed.knoweb.till.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.money.Qty
import lk.coopfed.knoweb.till.core.receipt.Block
import lk.coopfed.knoweb.till.core.receipt.ReceiptLayouts
import lk.coopfed.knoweb.till.core.receipt.SlipHeader
import lk.coopfed.knoweb.till.core.snapshot.Language

class SessionAndZReportTest {

    @Test
    fun aSessionOpensOnlyForASignedInCashierAndOnlyOncePerTill() = runTest {
        val till = TillFixture().enrolled()
        assertFailsWith<TillRefusal> { till.service.openSession(Money.parse("2000")) }

        till.service.signIn(till.service.catalogue.operators.single(), "1234")
        val session = till.service.openSession(Money.parse("2000"))

        assertEquals(LocalDate(2026, 9, 29), session.businessDate)
        assertFailsWith<TillRefusal> { till.service.openSession(Money.parse("2000")) }
    }

    @Test
    fun theBlindCountClosesTheSessionWithTheExpectedCashAndTheVariance() = runTest {
        val till = TillFixture().signedInWithSession(float = "2000.00")
        till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("5000"))
        till.service.sellForCash(till.basket(TillFixture.DHAL, TillFixture.DHAL, TillFixture.RICE), Money.parse("2021"))
        till.clock.advance(8.hours)

        val z = till.service.closeSession(Money.parse("5000.00"))

        assertEquals(Money.parse("3271.00"), z.grossSales)
        assertEquals(Money.parse("5271.00"), z.expectedCash)
        assertEquals(Money.parse("-271.00"), z.variance)
        assertEquals(2, z.receiptCount)
        assertEquals("S01-T2-1", z.firstReceipt)
        assertEquals("S01-T2-2", z.lastReceipt)
        assertEquals(listOf("Dhal 1kg" to Qty.of(2), "Rice 5kg" to Qty.of(2)), z.items.map { it.nameEn to it.qty })
        assertNull(till.service.currentSession())

        val closed = Json.parseToJsonElement(till.store.pendingOutbox(10).last().json).jsonObject.getValue("payload").jsonObject
        assertEquals("5271.00", closed.getValue("expected_cash").jsonPrimitive.content)
        assertEquals("-271.00", closed.getValue("variance").jsonPrimitive.content)
    }

    @Test
    fun theZReportCanBePrintedAgainAndIsTheSame() = runTest {
        val till = TillFixture().signedInWithSession()
        till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250"))
        val z = till.service.closeSession(Money.parse("3250"))

        assertEquals(z, till.service.zReport(z.session.sessionId))
        assertEquals(Money.ZERO, z.variance)
    }

    @Test
    fun theZReportLayoutPrintsInTheShopsLanguage() = runTest {
        val till = TillFixture().signedInWithSession()
        till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250"))
        val z = till.service.closeSession(Money.parse("3250"))

        val layout = ReceiptLayouts.zReport(z, SlipHeader("කුලියාපිටිය", "S01", "2"), Language.SI, till.clock.zone)
        val texts = layout.blocks.filterIsInstance<Block.Row>().map { it.left }

        assertEquals("Z වාර්තාව", (layout.blocks.first() as Block.Text).text)
        assertEquals(true, texts.contains("අපේක්ෂිත මුදල"))
        assertEquals(true, texts.any { it.startsWith("සහල් 5kg") })
    }

    @Test
    fun fiveWrongPinsLockTheTill() = runTest {
        val till = TillFixture().enrolled()
        val nimali = till.service.catalogue.operators.single()
        repeat(5) { assertFailsWith<TillRefusal> { till.service.signIn(nimali, "0000") } }

        val locked = assertFailsWith<TillRefusal> { till.service.signIn(nimali, "1234") }
        assertEquals(true, locked.message!!.startsWith("Too many wrong PINs"))
    }
}
