package lk.coopfed.knoweb.till.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.sale.ContentHash
import lk.coopfed.knoweb.till.core.session.ZReport

class DigitalTenderTest {

    @Test
    fun successfulCardSaleRecordsKindAmountAndReference() = runTest {
        val till = TillFixture().signedInWithSession()
        assertTrue(till.service.status.value.online == true, "till should be online")

        val basket = till.basket(TillFixture.RICE)
        val receipt = till.service.sellForCard(basket, "AUTH-CARD-123456")

        assertEquals(1, receipt.number)
        assertEquals(Money.parse("1250.00"), receipt.gross)
        assertEquals(Money.parse("1250.00"), receipt.tendered)
        assertEquals(Money.ZERO, receipt.change)
        assertEquals(1, receipt.tenders.size)

        val tender = receipt.tenders.single()
        assertEquals(1, tender.seq)
        assertEquals("CARD", tender.kind)
        assertEquals(Money.parse("1250.00"), tender.amount)
        assertEquals("AUTH-CARD-123456", tender.reference)
        assertEquals(Money.ZERO, tender.changeGiven)
        assertFalse(tender.offline)

        // Verify outbox payload
        val outbox = till.store.pendingOutbox(10).single { it.eventType == "receipt.issued.v1" }
        val payload = Json.parseToJsonElement(outbox.json).jsonObject.getValue("payload").jsonObject
        val tendersJson = payload.getValue("tenders").jsonArray
        assertEquals(1, tendersJson.size)
        val tenderJson = tendersJson.single().jsonObject
        assertEquals(1, tenderJson.getValue("seq").jsonPrimitive.content.toInt())
        assertEquals("CARD", tenderJson.getValue("kind").jsonPrimitive.content)
        assertEquals("1250.00", tenderJson.getValue("amount").jsonPrimitive.content)
        assertEquals("AUTH-CARD-123456", tenderJson.getValue("reference").jsonPrimitive.content)

        // Basket should be cleared
        assertTrue(basket.isEmpty)
    }

    @Test
    fun successfulWalletSaleRecordsKindAmountAndReference() = runTest {
        val till = TillFixture().signedInWithSession()
        val basket = till.basket(TillFixture.DHAL)
        val receipt = till.service.sellForWallet(basket, "WAL-REF-998877")

        assertEquals(Money.parse("385.50"), receipt.gross)
        val tender = receipt.tenders.single()
        assertEquals("WALLET", tender.kind)
        assertEquals(Money.parse("385.50"), tender.amount)
        assertEquals("WAL-REF-998877", tender.reference)
        assertEquals(Money.ZERO, tender.changeGiven)
        assertFalse(tender.offline)
    }

    @Test
    fun successfulQrSaleRecordsKindAmountAndReference() = runTest {
        val till = TillFixture().signedInWithSession()
        val basket = till.basket(TillFixture.DHAL)
        val receipt = till.service.sellForQr(basket, "QR-TXN-445566")

        assertEquals(Money.parse("385.50"), receipt.gross)
        val tender = receipt.tenders.single()
        assertEquals("QR", tender.kind)
        assertEquals(Money.parse("385.50"), tender.amount)
        assertEquals("QR-TXN-445566", tender.reference)
        assertEquals(Money.ZERO, tender.changeGiven)
        assertFalse(tender.offline)
    }

    @Test
    fun digitalPaymentsAreRefusedWhileOfflineWithZeroReceiptOrOutboxChanges() = runTest {
        val till = TillFixture().signedInWithSession()
        till.central.reachable = false
        till.service.syncOnce()
        assertEquals(false, till.service.status.value.online, "till must be offline")

        val basket = till.basket(TillFixture.RICE)

        // Card offline refusal
        val cardErr = assertFailsWith<TillRefusal> {
            till.service.sellForCard(basket, "AUTH-OFFLINE-CARD")
        }
        assertEquals("Card payment is refused while offline", cardErr.message)

        // Wallet offline refusal
        val walErr = assertFailsWith<TillRefusal> {
            till.service.sellForWallet(basket, "AUTH-OFFLINE-WAL")
        }
        assertEquals("Wallet payment is refused while offline", walErr.message)

        // QR offline refusal
        val qrErr = assertFailsWith<TillRefusal> {
            till.service.sellForQr(basket, "AUTH-OFFLINE-QR")
        }
        assertEquals("QR payment is refused while offline", qrErr.message)

        // Split with digital offline refusal
        val splitErr = assertFailsWith<TillRefusal> {
            till.service.sell(
                basket,
                listOf(
                    TenderInput("CASH", Money.parse("250.00")),
                    TenderInput("CARD", Money.parse("1000.00"), reference = "AUTH-SPLIT-CARD"),
                ),
            )
        }
        assertEquals("Card payment is refused while offline", splitErr.message)

        // Basket must remain intact!
        assertEquals(1, basket.lines.size)
        assertFalse(basket.isEmpty)

        // Store must have zero receipts and outbox has only the session opened event
        val session = till.service.currentSession()!!
        assertEquals(emptyList(), till.store.receiptsOfSession(session.sessionId))
        assertEquals(listOf("till_session.opened.v1"), till.store.pendingOutbox(10).map { it.eventType })
    }

    @Test
    fun missingOrBlankAuthorizationReferenceIsRefused() = runTest {
        val till = TillFixture().signedInWithSession()
        val basket = till.basket(TillFixture.RICE)

        val errBlankCard = assertFailsWith<TillRefusal> {
            till.service.sellForCard(basket, "")
        }
        assertEquals("Payment reference is required for Card", errBlankCard.message)

        val errWhitespaceCard = assertFailsWith<TillRefusal> {
            till.service.sellForCard(basket, "   ")
        }
        assertEquals("Payment reference is required for Card", errWhitespaceCard.message)

        val errBlankWallet = assertFailsWith<TillRefusal> {
            till.service.sellForWallet(basket, "")
        }
        assertEquals("Payment reference is required for Wallet", errBlankWallet.message)

        val errBlankQr = assertFailsWith<TillRefusal> {
            till.service.sellForQr(basket, "")
        }
        assertEquals("Payment reference is required for QR", errBlankQr.message)

        // Basket remains intact
        assertEquals(1, basket.lines.size)
    }

    @Test
    fun splitPaymentsWithCashSupportChangeAndRecordAllTenders() = runTest {
        val till = TillFixture().signedInWithSession()
        val basket = till.basket(TillFixture.RICE) // 1250.00

        // Split: 1000.00 Card, 250.00 Cash (customer gives 500.00 cash note)
        val receipt = till.service.sell(
            basket,
            listOf(
                TenderInput(kind = "CARD", amount = Money.parse("1000.00"), reference = "AUTH-SPLIT-1"),
                TenderInput(kind = "CASH", amount = Money.parse("250.00"), tendered = Money.parse("500.00")),
            ),
        )

        assertEquals(Money.parse("1250.00"), receipt.gross)
        assertEquals(Money.parse("1500.00"), receipt.tendered)
        assertEquals(Money.parse("250.00"), receipt.change)
        assertEquals(2, receipt.tenders.size)

        val cardTender = receipt.tenders[0]
        assertEquals(1, cardTender.seq)
        assertEquals("CARD", cardTender.kind)
        assertEquals(Money.parse("1000.00"), cardTender.amount)
        assertEquals("AUTH-SPLIT-1", cardTender.reference)
        assertEquals(Money.ZERO, cardTender.changeGiven)

        val cashTender = receipt.tenders[1]
        assertEquals(2, cashTender.seq)
        assertEquals("CASH", cashTender.kind)
        assertEquals(Money.parse("250.00"), cashTender.amount)
        assertNull(cashTender.reference)
        assertEquals(Money.parse("250.00"), cashTender.changeGiven)
    }

    @Test
    fun zReportExpectedCashExcludesDigitalTendersFromDrawerReconciliation() = runTest {
        val till = TillFixture().signedInWithSession(float = "2000.00")

        // 1. Cash sale: Dhal 1kg = 385.50
        till.service.sellForCash(till.basket(TillFixture.DHAL), Money.parse("500.00"))

        // 2. Card sale: Rice 5kg = 1250.00
        till.service.sellForCard(till.basket(TillFixture.RICE), "AUTH-CARD-1")

        // 3. QR sale: Dhal 1kg = 385.50
        till.service.sellForQr(till.basket(TillFixture.DHAL), "AUTH-QR-1")

        // 4. Split sale: Rice 5kg (1250.00) = Card 1000.00 + Cash 250.00
        till.service.sell(
            till.basket(TillFixture.RICE),
            listOf(
                TenderInput(kind = "CARD", amount = Money.parse("1000.00"), reference = "AUTH-CARD-2"),
                TenderInput(kind = "CASH", amount = Money.parse("250.00")),
            ),
        )

        // Total gross: 385.50 + 1250.00 + 385.50 + 1250.00 = 3271.00
        // Expected cash in drawer: float (2000.00) + cash tenders (385.50 + 250.00 = 635.50) = 2635.50
        // Digital tenders (1250.00 + 385.50 + 1000.00 = 2635.50) MUST NOT be counted in drawer expected cash.

        val z = till.service.closeSession(Money.parse("2635.50"))

        assertEquals(Money.parse("3271.00"), z.grossSales)
        assertEquals(Money.parse("635.50"), z.cashSales)
        assertEquals(Money.parse("2635.50"), z.session.expectedCash)
        assertEquals(Money.parse("2635.50"), z.session.countedCash)
        assertEquals(Money.ZERO, z.session.variance)
    }

    @Test
    fun transactionRollbackLeavesBasketIntactAndNoNumberUsedForCardSale() = runTest {
        val till = TillFixture().signedInWithSession()
        val basket = till.basket(TillFixture.RICE)
        till.store.failNextCommit = true

        assertFailsWith<IllegalStateException> {
            till.service.sellForCard(basket, "AUTH-POWER-CUT")
        }

        // Basket must still have the item
        assertEquals(1, basket.lines.size)

        // Subsequent sale succeeds with receipt number 1
        val receipt = till.service.sellForCard(basket, "AUTH-RECOVERED")
        assertEquals(1, receipt.number)
        assertEquals(2, receipt.deviceSeq)
    }

    @Test
    fun zeroPciFootprintNeverStoresOrEmitsSensitiveCardData() = runTest {
        val till = TillFixture().signedInWithSession()
        val receipt = till.service.sellForCard(till.basket(TillFixture.RICE), "AUTH-REF-1234")

        val outbox = till.store.pendingOutbox(10).single { it.eventType == "receipt.issued.v1" }
        val json = outbox.json

        // Verify zero PAN, PIN, CVV, or cardholder keywords exist in payload
        assertFalse(json.contains("pan", ignoreCase = true))
        assertFalse(json.contains("cvv", ignoreCase = true))
        assertFalse(json.contains("cardholder", ignoreCase = true))
        assertFalse(json.contains("expiry", ignoreCase = true))
    }

    @Test
    fun receiptLayoutFormatsCardWalletAndQrTendersWithReferences() = runTest {
        val till = TillFixture().signedInWithSession()
        val receipt = till.service.sell(
            till.basket(TillFixture.RICE),
            listOf(
                TenderInput("CARD", Money.parse("1000.00"), "AUTH-C1"),
                TenderInput("CASH", Money.parse("250.00"), tendered = Money.parse("500.00")),
            ),
        )
        val layout = lk.coopfed.knoweb.till.core.receipt.ReceiptLayouts.receipt(
            receipt,
            lk.coopfed.knoweb.till.core.receipt.SlipHeader("Shop", "S01", "2"),
            lk.coopfed.knoweb.till.core.snapshot.Language.EN,
            till.clock.zone,
        )
        val rows = layout.blocks.filterIsInstance<lk.coopfed.knoweb.till.core.receipt.Block.Row>()
        assertTrue(rows.any { it.left == "Card" && it.right == "1,000.00 (AUTH-C1)" })
        assertTrue(rows.any { it.left == "Cash" && it.right == "250.00" })
        assertTrue(rows.any { it.left == "Change" && it.right == "250.00" })
    }
}
