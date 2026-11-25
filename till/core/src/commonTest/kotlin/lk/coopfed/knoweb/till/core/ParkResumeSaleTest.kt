package lk.coopfed.knoweb.till.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lk.coopfed.knoweb.till.core.model.ParkedSaleLine
import lk.coopfed.knoweb.till.core.model.ParkedSaleRecord
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.money.Qty
import lk.coopfed.knoweb.till.core.port.Settings
import lk.coopfed.knoweb.till.core.sale.Facts
import lk.coopfed.knoweb.till.core.snapshot.SnapshotRow

class ParkResumeSaleTest {

    @Test
    fun aParkedSaleSurvivesRestartAndTakesAReceiptNumberOnlyWhenCompleted() = runTest {
        val colleagueId = "0190f0de-0000-7000-8000-0000000b0002"
        val till = TillFixture(
            operators = listOf(
                Samples.operator("pin:1234"),
                Samples.operator("pin:5678", colleagueId, "Kamal"),
            ),
        ).enrolled()
        till.service.signIn(till.service.catalogue.operators.first { it.userId == Samples.OPERATOR }, "1234")
        till.service.openSession(Money.parse("2000"))
        val basket = till.basket(TillFixture.RICE, TillFixture.DHAL).apply {
            setBeneficiary("MEMBER", "member-42")
        }

        val parked = till.service.parkSale(basket)

        assertTrue(basket.isEmpty)
        assertEquals("1", till.store.setting(Settings.NEXT_RECEIPT_NUMBER))
        assertEquals("2", till.store.setting(Settings.NEXT_DEVICE_SEQ))
        assertEquals(2, parked.lines.size)
        assertEquals("MEMBER", parked.customerClass)
        assertEquals("member-42", parked.beneficiaryIdentifier)

        till.restart()
        val colleague = till.service.catalogue.operators.first { it.userId == colleagueId }
        till.service.signIn(colleague, "5678")
        val (resumed, restored) = till.service.resumeParkedSale(parked.id)

        assertEquals(parked.id, resumed.id)
        assertEquals(listOf("Rice 5kg", "Dhal 1kg"), restored.lines.map { it.item.name.en })
        assertEquals(listOf("1250.00", "385.50"), restored.lines.map { it.unitPrice.plain() })
        assertEquals("MEMBER", restored.customerClass)
        assertEquals("member-42", restored.beneficiaryIdentifier)
        // Resume keeps the recovery copy until completion succeeds.
        assertEquals(parked.id, till.store.parkedSale(parked.id)?.id)

        val receipt = till.service.sellForCash(restored, Money.parse("2000"), parked.id)

        assertEquals(1, receipt.number)
        assertEquals(colleagueId, receipt.operatorUserId)
        assertEquals(null, till.store.parkedSale(parked.id))
        assertEquals(1, till.store.receiptsOfSession(receipt.sessionId).size)
    }

    @Test
    fun aFailedParkLeavesTheBasketAndCountersUnchanged() = runTest {
        val till = TillFixture().signedInWithSession()
        val basket = till.basket(TillFixture.RICE)
        till.store.failNextCommit = true

        assertFailsWith<IllegalStateException> { till.service.parkSale(basket) }

        assertEquals(1, basket.lines.size)
        assertEquals("1", till.store.setting(Settings.NEXT_RECEIPT_NUMBER))
        assertEquals("2", till.store.setting(Settings.NEXT_DEVICE_SEQ))
        assertTrue(till.service.parkedSales().isEmpty())
    }

    @Test
    fun aFailedResumedSaleKeepsItsRecoveryCopyAndRetriesOnce() = runTest {
        val till = TillFixture().signedInWithSession()
        val parked = till.service.parkSale(till.basket(TillFixture.RICE))
        val restored = till.service.resumeParkedSale(parked.id).second
        till.store.failNextCommit = true

        assertFailsWith<IllegalStateException> {
            till.service.sellForCash(restored, Money.parse("1250"), parked.id)
        }

        assertEquals(parked.id, till.store.parkedSale(parked.id)?.id)
        assertEquals(1, restored.lines.size)
        assertEquals("1", till.store.setting(Settings.NEXT_RECEIPT_NUMBER))

        val receipt = till.service.sellForCash(restored, Money.parse("1250"), parked.id)
        assertEquals(1, receipt.number)
        assertEquals(null, till.store.parkedSale(parked.id))
        assertEquals(1, till.store.receiptsOfSession(receipt.sessionId).size)
    }

    @Test
    fun closingTheSessionAbandonsEveryParkedSaleWithAReviewFact() = runTest {
        val till = TillFixture().signedInWithSession()
        val first = till.service.parkSale(till.basket(TillFixture.RICE))
        val second = till.service.parkSale(till.basket(TillFixture.DHAL))

        till.service.closeSession(Money.parse("2000"))

        assertTrue(till.store.parkedSalesOfSession(first.sessionId).isEmpty())
        val facts = till.store.pendingOutbox(20)
        assertEquals(
            listOf(
                "till_session.opened.v1",
                Facts.PARKED_SALE_ABANDONED_EVENT,
                Facts.PARKED_SALE_ABANDONED_EVENT,
                "till_session.closed.v1",
            ),
            facts.map { it.eventType },
        )
        val auditJson = facts.filter { it.eventType == Facts.PARKED_SALE_ABANDONED_EVENT }.map { it.json }
        assertTrue(auditJson.all { "TILL_PARKED_SALE_ABANDONED" in it && "SESSION_CLOSED" in it })
        assertTrue(auditJson.any { first.id in it })
        assertTrue(auditJson.any { second.id in it })
    }

    @Test
    fun theConfiguredAgeExpiresOnceAndCannotBeResumed() = runTest {
        val till = TillFixture(extraRows = listOf(config("pos.parked_sale_max_age", "PT1M"))).signedInWithSession()
        val parked = till.service.parkSale(till.basket(TillFixture.RICE))
        till.clock.advance(1.minutes)

        assertTrue(till.service.parkedSales().isEmpty())
        assertEquals(null, till.store.parkedSale(parked.id))
        val firstCount = till.store.pendingOutbox(20).count { it.eventType == Facts.PARKED_SALE_ABANDONED_EVENT }
        assertEquals(1, firstCount)

        assertTrue(till.service.parkedSales().isEmpty())
        assertEquals(firstCount, till.store.pendingOutbox(20).count { it.eventType == Facts.PARKED_SALE_ABANDONED_EVENT })
    }

    @Test
    fun aParkedSaleFromAnotherSessionCannotBeResumed() = runTest {
        val till = TillFixture().signedInWithSession()
        val current = till.service.currentSession()!!
        val other = ParkedSaleRecord(
            id = "parked-elsewhere",
            sessionId = "another-session",
            lines = listOf(
                ParkedSaleLine(
                    Samples.SKU_RICE, "RICE", "Rice", null, null, "EA", false, emptyList(), Qty.ONE,
                    Money.parse("100"),
                ),
            ),
            parkedAt = current.openedAt,
            operatorUserId = Samples.OPERATOR,
        )
        till.store.saveParkedSale(other)

        val failure = assertFailsWith<TillRefusal> { till.service.resumeParkedSale(other.id) }

        assertTrue(failure.message.orEmpty().contains("original session"))
        assertEquals(other, till.store.parkedSale(other.id))
    }

    @Test
    fun anOperatorWithoutReceiptPermissionCannotReadOrResumeParkedSales() = runTest {
        val viewerId = "0190f0de-0000-7000-8000-0000000b0003"
        val till = TillFixture(
            operators = listOf(
                Samples.operator("pin:1234"),
                Samples.operator("pin:9999", viewerId, "Viewer", emptyList()),
            ),
        ).enrolled()
        till.service.signIn(till.service.catalogue.operators.first { it.userId == Samples.OPERATOR }, "1234")
        till.service.openSession(Money.ZERO)
        val parked = till.service.parkSale(till.basket(TillFixture.RICE))
        till.service.switchOperator(till.service.catalogue.operators.first { it.userId == viewerId }, "9999")

        assertFailsWith<TillRefusal> { till.service.parkedSales() }
        assertFailsWith<TillRefusal> { till.service.resumeParkedSale(parked.id) }
        assertEquals(parked, till.store.parkedSale(parked.id))
    }

    private fun config(key: String, value: String) = SnapshotRow(
        "config",
        key,
        null,
        buildJsonObject { put("key", key); put("value", value) },
    )
}
