package lk.coopfed.knoweb.till.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import lk.coopfed.knoweb.till.core.money.Money

class OfflineQueueTest {

    @Test
    fun theTillSellsWithNoNetworkAndKeepsEveryFactUntilCentralHasIt() = runTest {
        val till = TillFixture().signedInWithSession()
        till.central.reachable = false

        repeat(3) { till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250")) }
        assertEquals(0, till.service.syncOnce())

        assertEquals(false, till.service.status.value.online)
        assertEquals(4, till.store.pendingCount())
        assertEquals(0, till.central.batches.size)
    }

    @Test
    fun whenCentralIsBackTheOutboxDrainsInOrderInBatches() = runTest {
        val till = TillFixture().signedInWithSession()
        till.central.reachable = false
        repeat(4) { till.service.sellForCash(till.basket(TillFixture.DHAL), Money.parse("400")) }

        till.central.reachable = true
        val sent = till.service.syncOnce(batchSize = 2)

        assertEquals(5, sent)
        assertEquals(0, till.store.pendingCount())
        assertEquals(listOf(1L to 2L, 3L to 4L, 5L to 5L), till.central.batches.map { it.range() })
        assertEquals(true, till.service.status.value.online)
    }

    @Test
    fun aBatchSentAgainAfterALostAnswerKeepsItsBatchId() = runTest {
        val till = TillFixture().signedInWithSession()
        till.central.loseNextAnswer = true
        till.service.syncOnce()
        assertEquals(false, till.service.status.value.online)
        till.service.syncOnce()

        val (lost, retried) = till.central.batches
        assertEquals(lost.range(), retried.range())
        assertEquals(lost.batchId(), retried.batchId())
    }

    @Test
    fun whatCentralDidNotApplyGoesAgainInANewBatch() = runTest {
        val till = TillFixture().signedInWithSession()
        till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250"))
        till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250"))
        till.central.applyAtMost = 1

        till.service.syncOnce()

        val (first, second) = till.central.batches
        assertEquals(1L to 3L, first.range())
        assertEquals(2L to 3L, second.range())
        assertNotEquals(first.batchId(), second.batchId())
    }

    private fun JsonObject.range() = getValue("first_seq").jsonPrimitive.content.toLong() to getValue("last_seq").jsonPrimitive.content.toLong()
    private fun JsonObject.batchId() = getValue("batch_id").jsonPrimitive.content
    @Suppress("unused")
    private fun JsonObject.events() = getValue("events").jsonArray
}
