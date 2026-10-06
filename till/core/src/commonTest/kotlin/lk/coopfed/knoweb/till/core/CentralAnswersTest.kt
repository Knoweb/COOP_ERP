package lk.coopfed.knoweb.till.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lk.coopfed.knoweb.till.core.model.Anomaly
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.port.EventOutcome
import lk.coopfed.knoweb.till.core.port.Instruction
import lk.coopfed.knoweb.till.core.receipt.Block
import lk.coopfed.knoweb.till.core.receipt.ReceiptLayouts
import lk.coopfed.knoweb.till.core.receipt.SlipHeader
import lk.coopfed.knoweb.till.core.snapshot.Language

/** What the till does with central's answers (TWK-05; decision 2026-10-06-wave2-till-trust-and-durability (3)). */
class CentralAnswersTest {

    // ---- outcomes ----

    @Test
    fun aQuarantinedFactIsAProblemForTheOfficeAndOnTheZReportWhileTheReceiptStands() = runTest {
        val till = TillFixture().signedInWithSession()
        val receipt = till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250"))
        till.central.outcomes[receipt.deviceSeq] = EventOutcome(receipt.deviceSeq, "e2", EventOutcome.QUARANTINED, "HASH")

        till.service.syncOnce()

        assertEquals(1, till.service.status.value.problems)
        val problem = till.service.problems().single()
        assertEquals(Anomaly.QUARANTINED, problem.kind)
        assertEquals(receipt.deviceSeq, problem.deviceSeq)
        assertEquals("HASH", problem.reason)
        assertEquals(0, till.store.pendingCount(), "central moved its cursor past it: nothing waits")

        val z = till.service.closeSession(Money.parse("3250"))
        assertEquals(1, z.refusedFacts)
        assertEquals(listOf(receipt), till.store.receiptsOfSession(z.session.sessionId))
        val texts = ReceiptLayouts.zReport(z, SlipHeader("Shop", "S01", "2"), Language.EN, till.clock.zone).blocks
            .filterIsInstance<Block.Text>().map { it.text }
        assertTrue("1 facts refused by central, see the office" in texts)

        till.service.markProblemsSeen()
        assertEquals(0, till.service.status.value.problems)
        assertEquals(1, till.service.problems().size, "seen problems stay recorded")
    }

    @Test
    fun aZReportWithNothingRefusedSaysNothingAboutIt() = runTest {
        val till = TillFixture().signedInWithSession()
        till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250"))
        till.service.syncOnce()
        val z = till.service.closeSession(Money.parse("3250"))

        assertEquals(0, z.refusedFacts)
        val texts = ReceiptLayouts.zReport(z, SlipHeader("Shop", "S01", "2"), Language.EN, till.clock.zone).blocks
            .filterIsInstance<Block.Text>().map { it.text }
        assertTrue(texts.none { it.contains("refused") })
    }

    // ---- retention ----

    @Test
    fun acknowledgedFactsAreKeptForTheRetentionWindowThenPurged() = runTest {
        val till = TillFixture().signedInWithSession()
        till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250"))
        till.service.syncOnce()
        assertEquals(0, till.store.pendingCount())
        assertEquals(listOf(1L, 2L), till.store.outboxSeqs())

        till.clock.advance(6.days)
        till.service.syncOnce()
        assertEquals(listOf(1L, 2L), till.store.outboxSeqs())

        till.clock.advance(2.days)
        till.service.syncOnce()
        assertEquals(emptyList(), till.store.outboxSeqs())
    }

    @Test
    fun centralsRetentionDaysFromTheSnapshotReplaceTheDefault() = runTest {
        val till = TillFixture()
        val config = lk.coopfed.knoweb.till.core.snapshot.SnapshotRow(
            "config", "0190f0de-0000-7000-8000-0000000f0001", null,
            buildJsonObject { put("key", "sync.outbox.retention_days"); put("value", "2") },
        )
        till.central.snapshotAnswer = Samples.snapshotAnswer(7, till.rows + config)
        till.signedInWithSession()
        till.service.syncOnce()

        till.clock.advance(3.days)
        till.service.syncOnce()

        assertEquals(emptyList(), till.store.outboxSeqs())
    }

    // ---- RESEND_FROM and 409 ----

    @Test
    fun resendFromSendsTheRetainedFactsAgain() = runTest {
        val till = TillFixture().signedInWithSession()
        repeat(2) { till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250")) }
        till.service.syncOnce()

        till.central.nextInstructions += Instruction(Instruction.RESEND_FROM, 2, null)
        till.service.sellForCash(till.basket(TillFixture.DHAL), Money.parse("400"))
        till.service.syncOnce()

        assertEquals(listOf(1L to 3L, 4L to 4L, 2L to 4L), till.central.batches.map { it.range() })
        assertEquals(0, till.store.pendingCount())
        assertEquals(0, till.service.status.value.problems)
    }

    @Test
    fun resendFromAFactAlreadyPurgedIsAProblemAndStopsTheUpload() = runTest {
        val till = TillFixture().signedInWithSession()
        till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250"))
        till.service.syncOnce()
        till.clock.advance(8.days)
        till.service.syncOnce()

        till.central.nextInstructions += Instruction(Instruction.RESEND_FROM, 1, null)
        till.service.sellForCash(till.basket(TillFixture.DHAL), Money.parse("400"))
        till.service.syncOnce()

        val problem = till.service.problems().first { it.kind == Anomaly.RESEND_IMPOSSIBLE }
        assertEquals(1, problem.deviceSeq)
        assertTrue(till.service.status.value.banners.any { it.startsWith("Uploading is stopped") })
    }

    @Test
    fun aSequenceGapIsAnsweredByResendingFromWhereCentralsCursorIs() = runTest {
        val till = TillFixture().signedInWithSession()
        till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250"))
        till.service.syncOnce()
        till.service.sellForCash(till.basket(TillFixture.DHAL), Money.parse("400"))
        till.central.uploadRefusals += Samples.sequenceGap(2)

        till.service.syncOnce()

        assertEquals(listOf(1L to 2L, 3L to 3L, 2L to 3L), till.central.batches.map { it.range() })
        assertEquals(0, till.store.pendingCount())
    }

    @Test
    fun aSequenceGapTheTillCannotFillIsAProblemAndTheFactsWait() = runTest {
        val till = TillFixture().signedInWithSession()
        till.service.syncOnce()
        till.clock.advance(8.days)
        till.service.syncOnce()
        till.service.sellForCash(till.basket(TillFixture.DHAL), Money.parse("400"))
        till.central.uploadRefusals += Samples.sequenceGap(1)

        till.service.syncOnce()

        assertEquals(1, till.store.pendingCount())
        assertEquals(1, till.service.problems().single { it.kind == Anomaly.RESEND_IMPOSSIBLE }.deviceSeq)
        assertEquals("Central expects facts from 1; see the problems", till.service.status.value.message)
    }

    // ---- FLOOR_NOTICE ----

    @Test
    fun aFloorNoticeIsABannerUntilAnAckComesWithoutIt() = runTest {
        val till = TillFixture().signedInWithSession()
        till.central.nextInstructions += Instruction(Instruction.FLOOR_NOTICE, null, "0.2.0")
        till.service.syncOnce()
        assertTrue(till.service.status.value.banners.any { it.contains("below central's minimum") })

        till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250"))
        till.service.syncOnce()
        assertTrue(till.service.status.value.banners.none { it.contains("below central's minimum") })
    }

    // ---- revoke (decision D-3) ----

    @Test
    fun aSignedRevokeLocksTheTillDeletesTheSnapshotAndKeepsTheOutbox() = runTest {
        val till = TillFixture().signedInWithSession()
        val receipt = till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250"))
        till.central.uploadRefusals += Samples.revoke()

        till.service.syncOnce()

        assertTrue(till.service.status.value.revoked)
        assertEquals(0, till.store.loadSnapshot().version)
        assertTrue(till.service.catalogue.items.isEmpty())
        assertEquals(2, till.store.pendingCount(), "the unsent facts are kept")
        assertEquals(listOf(receipt), till.store.receiptsOfSession(receipt.sessionId))
        assertFailsWith<TillRefusal> { till.service.sellForCash(till.basket(), Money.parse("0")) }

        // A restart does not unlock it.
        assertTrue(till.restart().isRevoked)
    }

    @Test
    fun aRevokedTillUploadsItsKeptOutboxWhenTheOfficeReinstatesIt() = runTest {
        val till = TillFixture().signedInWithSession()
        till.service.sellForCash(till.basket(TillFixture.RICE), Money.parse("1250"))
        till.central.uploadRefusals += Samples.revoke()
        till.service.syncOnce()

        // Still suspended: the heartbeat is refused again and nothing is uploaded.
        till.central.heartbeatRefusal = Samples.revoke(issuedAt = "2026-09-29T05:10:00Z")
        till.service.syncOnce()
        assertTrue(till.service.status.value.revoked)
        assertEquals(1, till.central.batches.size)

        // Reinstated: uploading resumes from the last acknowledged sequence on, and the snapshot comes back.
        till.central.heartbeatRefusal = null
        till.central.heartbeatAnswer = buildJsonObject { put("snapshot_version", 7) }
        till.service.syncOnce()

        assertFalse(till.service.status.value.revoked)
        assertEquals(0, till.store.pendingCount())
        assertEquals(1L to 2L, till.central.batches.last().range())
        assertEquals(7, till.store.loadSnapshot().version)
    }

    @Test
    fun aRevokeTheTillCannotVerifyIsIgnored() = runTest {
        val forged = Samples.revoke(signingKey = "another-key")
        val otherDevice = Samples.revoke(deviceId = "0190f0de-0000-7000-8000-0000000c0099")
        val beforeEnrolment = Samples.revoke(issuedAt = "2026-09-29T04:00:00Z")
        for (revoke in listOf(forged, otherDevice, beforeEnrolment)) {
            val till = TillFixture().signedInWithSession()
            till.central.uploadRefusals += revoke

            till.service.syncOnce()

            assertFalse(till.service.status.value.revoked, revoke.problem)
            assertEquals(7, till.store.loadSnapshot().version)
            assertEquals("Central refused (403) with a revoke this till could not verify; it is ignored", till.service.status.value.message)
        }
    }

    private fun JsonObject.range() = getValue("first_seq").jsonPrimitive.content.toLong() to getValue("last_seq").jsonPrimitive.content.toLong()
}
