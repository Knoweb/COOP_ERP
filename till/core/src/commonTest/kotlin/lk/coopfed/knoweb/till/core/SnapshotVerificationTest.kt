package lk.coopfed.knoweb.till.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lk.coopfed.knoweb.till.core.crypto.Sha256
import lk.coopfed.knoweb.till.core.json.CanonicalJson
import lk.coopfed.knoweb.till.core.snapshot.SnapshotDelta
import lk.coopfed.knoweb.till.core.snapshot.SnapshotRejected
import lk.coopfed.knoweb.till.core.snapshot.SnapshotRow
import lk.coopfed.knoweb.till.core.snapshot.SnapshotState
import lk.coopfed.knoweb.till.core.snapshot.SnapshotVerifier

class SnapshotVerificationTest {

    private val verifier = SnapshotVerifier(FakeSignatures)
    private val rows = listOf(
        Samples.location(),
        Samples.sku(Samples.SKU_RICE, "Rice 5kg", "සහල් 5kg", "4790000000011"),
    )

    @Test
    fun aSignedSnapshotWhoseTablesMatchTheManifestIsAccepted() {
        val delta = SnapshotDelta.fromJson(Samples.snapshotAnswer(3, rows))
        verifier.verify(delta, Samples.LOCATION, Samples.PUBLIC_KEY)
        val state = SnapshotState.EMPTY.apply(delta, LocalDate(2026, 9, 29))
        assertEquals(3, state.version)
        assertEquals(1, state.table("sku").size)
    }

    @Test
    fun aRowChangedOnTheWayIsRefusedByItsTableHash() {
        val delta = SnapshotDelta.fromJson(Samples.tampered(Samples.snapshotAnswer(3, rows)))
        val refusal = assertFailsWith<SnapshotRejected> { verifier.verify(delta, Samples.LOCATION, Samples.PUBLIC_KEY) }
        assertEquals("The hash of table sku does not match its manifest", refusal.message)
    }

    @Test
    fun aManifestNotSignedByCentralsKeyIsRefused() {
        val answer = Samples.snapshotAnswer(3, rows)
        val forged = JsonObject(answer + ("signature" to JsonPrimitive(FakeSignatures.sign("another-key", answer["manifest"].toString()))))
        val refusal = assertFailsWith<SnapshotRejected> {
            verifier.verify(SnapshotDelta.fromJson(forged), Samples.LOCATION, Samples.PUBLIC_KEY)
        }
        assertEquals("The snapshot manifest is not signed by central", refusal.message)
    }

    @Test
    fun anotherShopsSnapshotIsRefused() {
        val delta = SnapshotDelta.fromJson(Samples.snapshotAnswer(3, rows, location = "0190f0de-0000-7000-8000-000000000133"))
        assertFailsWith<SnapshotRejected> { verifier.verify(delta, Samples.LOCATION, Samples.PUBLIC_KEY) }
    }

    @Test
    fun aTamperedSnapshotLeavesTheTillSellingWithTheOneItHas() = runTest {
        val till = TillFixture().enrolled()
        till.central.snapshotAnswer = Samples.tampered(Samples.snapshotAnswer(8, rows))
        assertFailsWith<SnapshotRejected> { till.service.refreshSnapshot() }
        assertEquals(7, till.store.loadSnapshot().version)
        assertNotNull(till.service.catalogue.byBarcode(TillFixture.DHAL))
    }

    // ---- continuity and freshness (TWK-01) ----

    private val sugar = Samples.sku("0190f0de-0000-7000-8000-0000000a0003", "Sugar 1kg", "සීනි 1kg", "4790000000035")

    @Test
    fun aSignedSnapshotOlderThanTheOneTheTillHoldsIsRefused() = runTest {
        val till = TillFixture().enrolled()
        till.central.snapshotAnswer = Samples.snapshotAnswer(6, till.rows)

        val refusal = assertFailsWith<SnapshotRejected> { till.service.refreshSnapshot() }

        assertEquals("The snapshot is version 6, older than the version 7 this till holds", refusal.message)
        assertEquals(7, till.store.loadSnapshot().version)
    }

    @Test
    fun aDeltaFromAnotherVersionIsNotAppliedAndAFullSnapshotIsAskedForInstead() = runTest {
        val till = TillFixture().enrolled()
        val gapped = Samples.snapshotAnswer(11, listOf(sugar), since = 10, full = false)
        val full = Samples.snapshotAnswer(11, till.rows + sugar)
        till.central.snapshotFor = { since -> if (since == 0L) full else gapped }

        assertEquals(11, till.service.refreshSnapshot())

        assertEquals(listOf(0L, 7L, 0L), till.central.snapshotRequests)
        assertNotNull(till.service.catalogue.byBarcode("4790000000035"))
        assertNotNull(till.service.catalogue.byBarcode(TillFixture.DHAL))
    }

    @Test
    fun aGappedDeltaIsRefusedWhenNoFullSnapshotComesBack() = runTest {
        val till = TillFixture().enrolled()
        till.central.snapshotAnswer = Samples.snapshotAnswer(11, listOf(sugar), since = 10, full = false)

        val refusal = assertFailsWith<SnapshotRejected> { till.service.refreshSnapshot() }

        assertEquals("The snapshot delta starts at version 10, but this till holds version 7", refusal.message)
        assertEquals(7, till.store.loadSnapshot().version)
        assertNull(till.service.catalogue.byBarcode("4790000000035"))
    }

    @Test
    fun aDeltaFromTheVersionTheTillHoldsIsApplied() = runTest {
        val till = TillFixture().enrolled()
        val dhalGone = SnapshotRow("sku", Samples.SKU_DHAL, null, null)
        till.central.snapshotAnswer = Samples.snapshotAnswer(8, listOf(sugar, dhalGone), since = 7, full = false)

        assertEquals(8, till.service.refreshSnapshot())

        assertNotNull(till.service.catalogue.byBarcode("4790000000035"))
        assertNull(till.service.catalogue.byBarcode(TillFixture.DHAL))
        assertNotNull(till.service.catalogue.byBarcode(TillFixture.RICE))
    }

    @Test
    fun aFullSnapshotIsAcceptedWhateverSinceItCarries() = runTest {
        val till = TillFixture().enrolled()
        till.central.snapshotAnswer = Samples.snapshotAnswer(9, till.rows, since = 3)

        assertEquals(9, till.service.refreshSnapshot())
    }

    @Test
    fun aSnapshotSignedWithAnotherKeyThanTheEnrolledOneIsRefused() = runTest {
        val till = TillFixture().enrolled()
        till.central.snapshotAnswer = Samples.snapshotAnswer(8, till.rows, keyId = "k2")

        val refusal = assertFailsWith<SnapshotRejected> { till.service.refreshSnapshot() }

        assertEquals("The snapshot is signed with key \"k2\", not with the key this till enrolled with (\"k1\")", refusal.message)
        assertEquals(7, till.store.loadSnapshot().version)
    }

    @Test
    fun theSupervisorIsWarnedWhenCentralHasNotConfirmedTheSnapshotForThreeDays() = runTest {
        val till = TillFixture().enrolled()
        till.clock.advance(2.days)
        till.service.syncOnce()
        assertTrue(till.service.status.value.banners.none { it.startsWith("The snapshot was last confirmed") })

        till.clock.advance(2.days)
        till.service.syncOnce()
        assertTrue(till.service.status.value.banners.any { it.startsWith("The snapshot was last confirmed") })

        till.service.refreshSnapshot()
        assertTrue(till.service.status.value.banners.none { it.startsWith("The snapshot was last confirmed") })
    }

    @Test
    fun rowsForALaterBusinessDateAreHeldUntilThatDayOpens() {
        val later = SnapshotRow("price", "p-late", LocalDate(2026, 10, 1), buildJsonObject { put("sku_id", "x"); put("unit_price", "1.00") })
        val delta = SnapshotDelta.fromJson(Samples.snapshotAnswer(4, rows + later))
        val state = SnapshotState.EMPTY.apply(delta, LocalDate(2026, 9, 29))
        assertEquals(0, state.table("price").size)
        assertEquals(1, state.held.size)
        assertEquals(1, state.dayOpen(LocalDate(2026, 10, 1)).table("price").size)
    }

    @Test
    fun theCanonicalJsonSortsKeysAtEveryLevelAndEscapesLikeCentral() {
        val data = buildJsonObject {
            put("b", "x\"y\n")
            put("a", buildJsonObject { put("z", 1); put("m", true); put("k", null as String?) })
            put("c", "සහල්")
        }
        assertEquals("{\"a\":{\"k\":null,\"m\":true,\"z\":1},\"b\":\"x\\\"y\\n\",\"c\":\"සහල්\"}", CanonicalJson.write(data))
    }

    @Test
    fun sha256MatchesTheStandardVectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(""))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }
}
