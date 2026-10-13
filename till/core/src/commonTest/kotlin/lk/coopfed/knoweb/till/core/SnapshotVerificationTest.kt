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
import kotlinx.serialization.json.putJsonArray
import lk.coopfed.knoweb.till.core.crypto.Sha256
import lk.coopfed.knoweb.till.core.json.CanonicalJson
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.snapshot.Catalogue
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
    fun aNewlyRegisteredBarcodeScansAtAnEnrolledTillAfterNextDelta() = runTest {
        val till = TillFixture().enrolled()
        val newGtin = "4791234567891"

        // 1. Barcode is initially unknown on the till
        assertNull(till.service.catalogue.byBarcode(newGtin))

        // 2. Central registers the new barcode on existing Rice SKU and sends updated SKU in delta
        val riceWithNewBarcode = SnapshotRow(
            "sku", Samples.SKU_RICE, null,
            buildJsonObject {
                put("sku_code", "RICE")
                put("status", "SHARED")
                put("short_name_en", "Rice 5kg")
                put("short_name_si", "සහල් 5kg")
                put("base_uom_code", "EA")
                put("sold_by_weight", false)
                putJsonArray("barcodes") {
                    add(buildJsonObject { put("barcode", TillFixture.RICE) })
                    add(buildJsonObject { put("barcode", newGtin) })
                }
            },
        )
        till.central.snapshotAnswer = Samples.snapshotAnswer(8, listOf(riceWithNewBarcode), since = 7, full = false)

        assertEquals(8, till.service.refreshSnapshot())

        // 3. Offline till barcode lookup resolves the newly synced barcode to the right item
        val item = till.service.catalogue.byBarcode(newGtin)
        assertNotNull(item)
        assertEquals(Samples.SKU_RICE, item.skuId)
        assertEquals("Rice 5kg", item.name.en)
        assertNotNull(till.service.catalogue.byBarcode(TillFixture.RICE))

        // 4. When the barcode is retired at central, next delta updates the SKU without it
        val riceBarcodeRetired = Samples.sku(Samples.SKU_RICE, "Rice 5kg", "සහල් 5kg", TillFixture.RICE)
        till.central.snapshotAnswer = Samples.snapshotAnswer(9, listOf(riceBarcodeRetired), since = 8, full = false)

        assertEquals(9, till.service.refreshSnapshot())

        // 5. Retired barcode no longer resolves
        assertNull(till.service.catalogue.byBarcode(newGtin))
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

    @Test
    fun m3PricingTablesAreDeliveredVerifiedAndAuthoritativeOverLegacyPrice() {
        val skuRice = Samples.sku(Samples.SKU_RICE, "Rice 5kg", "සහල් 5kg", "4790000000011")
        val retailLine = SnapshotRow(
            "price_list_line",
            "pll-01",
            null,
            buildJsonObject {
                put("sku_id", Samples.SKU_RICE)
                put("uom_code", "EA")
                put("price", "1040.00")
                put("unit_price", "1040.00")
                put("tier_from_qty", "0.000")
                put("effective_from", "2026-10-01")
            },
        )
        val legacyPrice = SnapshotRow(
            "price",
            "p-legacy",
            null,
            buildJsonObject {
                put("sku_id", Samples.SKU_RICE)
                put("unit_price", "999.00")
            },
        )
        val discountRule = SnapshotRow(
            "discount_rule",
            "rule-01",
            null,
            buildJsonObject {
                put("name", "Tea promo")
                put("kind", "TIME_LIMITED_PRICE")
                put("priority", 100)
                put("valid_from", "2026-10-01")
                put("predicate", buildJsonObject { put("sku_id", Samples.SKU_RICE) })
                put("benefit", buildJsonObject { put("kind", "FIXED_PRICE"); put("value", "1000.00") })
            },
        )
        val controlPrice = SnapshotRow(
            "control_price",
            "cp-01",
            null,
            buildJsonObject {
                put("sku_id", Samples.SKU_RICE)
                put("ceiling_price", "1100.00")
                put("ceiling_uom_code", "EA")
                put("effective_from", "2026-10-01")
                put("gazette_reference", "Gazette 2401/12")
            },
        )
        val mrpPolicy = SnapshotRow(
            "mrp_policy",
            "mp-01",
            null,
            buildJsonObject {
                put("sku_id", Samples.SKU_RICE)
                put("policy", "PICKER")
                put("picker_gap_amount", "20.00")
                put("picker_gap_percent", "5.00")
            },
        )

        val delta = SnapshotDelta.fromJson(
            Samples.snapshotAnswer(
                5,
                listOf(Samples.location(), skuRice, retailLine, legacyPrice, discountRule, controlPrice, mrpPolicy),
            ),
        )
        verifier.verify(delta, Samples.LOCATION, Samples.PUBLIC_KEY)
        val state = SnapshotState.EMPTY.apply(delta, LocalDate(2026, 10, 1))

        assertEquals(1, state.table("price_list_line").size)
        assertEquals(1, state.table("discount_rule").size)
        assertEquals(1, state.table("control_price").size)
        assertEquals(1, state.table("mrp_policy").size)

        val catalogue = Catalogue(state)
        val item = catalogue.byBarcode("4790000000011")
        assertNotNull(item)
        // Authoritative M3 retail line (1040.00) takes precedence over legacy price (999.00)
        assertEquals(Money.parse("1040.00"), item.price)
    }

    @Test
    fun futureDatedPriceLineAndTombstoneAreHeldAndActivateOnDayOpen() {
        val skuRice = Samples.sku(Samples.SKU_RICE, "Rice 5kg", "සහල් 5kg", "4790000000011")
        val currentLine = SnapshotRow(
            "price_list_line",
            "pll-current",
            LocalDate(2026, 10, 1),
            buildJsonObject {
                put("sku_id", Samples.SKU_RICE)
                put("uom_code", "EA")
                put("price", "1000.00")
            },
        )
        val futureLine = SnapshotRow(
            "price_list_line",
            "pll-future",
            LocalDate(2026, 10, 15),
            buildJsonObject {
                put("sku_id", Samples.SKU_RICE)
                put("uom_code", "EA")
                put("price", "1050.00")
            },
        )
        val futureTombstone = SnapshotRow("price_list_line", "pll-current", LocalDate(2026, 10, 15), null)

        val delta = SnapshotDelta.fromJson(
            Samples.snapshotAnswer(
                6,
                listOf(skuRice, currentLine, futureLine, futureTombstone),
            ),
        )
        val state = SnapshotState.EMPTY.apply(delta, LocalDate(2026, 10, 1))

        // On Oct 1: futureLine and futureTombstone are held; currentLine is live
        assertEquals(1, state.table("price_list_line").size)
        assertEquals(2, state.held.size)
        assertEquals(Money.parse("1000.00"), Catalogue(state).byBarcode("4790000000011")?.price)

        // On Oct 15 dayOpen: futureLine goes live, futureTombstone deletes currentLine
        val nextState = state.dayOpen(LocalDate(2026, 10, 15))
        assertEquals(0, nextState.held.size)
        assertEquals(1, nextState.table("price_list_line").size)
        assertEquals(Money.parse("1050.00"), Catalogue(nextState).byBarcode("4790000000011")?.price)
    }

    @Test
    fun strictSkuAndUomMatchingAndTierFiltering() {
        val skuRice = Samples.sku(Samples.SKU_RICE, "Rice 5kg", "සහල් 5kg", "4790000000011")
        val pkLine = SnapshotRow(
            "price_list_line",
            "pll-pk",
            null,
            buildJsonObject {
                put("sku_id", Samples.SKU_RICE)
                put("uom_code", "PK")
                put("price", "2080.00")
            },
        )
        val tierLine = SnapshotRow(
            "price_list_line",
            "pll-tier",
            null,
            buildJsonObject {
                put("sku_id", Samples.SKU_RICE)
                put("uom_code", "EA")
                put("tier_from_qty", "10.000")
                put("price", "950.00")
            },
        )
        val shelfLine = SnapshotRow(
            "price_list_line",
            "pll-shelf",
            null,
            buildJsonObject {
                put("sku_id", Samples.SKU_RICE)
                put("uom_code", "EA")
                put("tier_from_qty", "0.000")
                put("price", "1040.00")
            },
        )

        // When only PK line is present, EA item has no match (strict UOM matching)
        val statePkOnly = SnapshotState.EMPTY.apply(
            SnapshotDelta.fromJson(Samples.snapshotAnswer(7, listOf(skuRice, pkLine))),
            LocalDate(2026, 10, 1),
        )
        assertNull(Catalogue(statePkOnly).byBarcode("4790000000011")?.price)

        // When both volume tier and base shelf lines are present, shelf line is selected
        val stateWithShelf = SnapshotState.EMPTY.apply(
            SnapshotDelta.fromJson(Samples.snapshotAnswer(8, listOf(skuRice, pkLine, tierLine, shelfLine))),
            LocalDate(2026, 10, 1),
        )
        assertEquals(Money.parse("1040.00"), Catalogue(stateWithShelf).byBarcode("4790000000011")?.price)
    }

    @Test
    fun futureEffectiveDateAndExpiredPricingRowsAreIgnored() {
        val skuRice = Samples.sku(Samples.SKU_RICE, "Rice 5kg", "සහල් 5kg", "4790000000011")
        val expiredLine = SnapshotRow(
            "price_list_line",
            "pll-exp",
            null,
            buildJsonObject {
                put("sku_id", Samples.SKU_RICE)
                put("uom_code", "EA")
                put("effective_from", "2026-09-01")
                put("effective_to", "2026-09-30")
                put("price", "900.00")
            },
        )
        val futureLine = SnapshotRow(
            "price_list_line",
            "pll-fut",
            null,
            buildJsonObject {
                put("sku_id", Samples.SKU_RICE)
                put("uom_code", "EA")
                put("effective_from", "2026-10-15")
                put("price", "1100.00")
            },
        )

        val state = SnapshotState.EMPTY.apply(
            SnapshotDelta.fromJson(Samples.snapshotAnswer(9, listOf(skuRice, expiredLine, futureLine))),
            LocalDate(2026, 10, 1),
        )

        // On 2026-10-01: expired line is past effective_to; future line is ahead of business date
        val catalogueOct1 = Catalogue(state, businessDate = LocalDate(2026, 10, 1))
        assertNull(catalogueOct1.byBarcode("4790000000011")?.price)

        // On 2026-10-15: future line becomes effective
        val catalogueOct15 = Catalogue(state, businessDate = LocalDate(2026, 10, 15))
        assertEquals(Money.parse("1100.00"), catalogueOct15.byBarcode("4790000000011")?.price)
    }

    @Test
    fun authoritativeM3PricingSuppressesObsoleteLegacyPriceFallbackEvenWhenLineMissing() {
        val skuRice = Samples.sku(Samples.SKU_RICE, "Rice 5kg", "සහල් 5kg", "4790000000011")
        val skuDhal = Samples.sku(Samples.SKU_DHAL, "Dhal 1kg", "පරිප්පු 1kg", "4790000000028")

        // Snapshot has M3 price list for Rice, but Dhal price was withdrawn / omitted
        val riceLine = SnapshotRow(
            "price_list_line",
            "pll-rice",
            null,
            buildJsonObject {
                put("sku_id", Samples.SKU_RICE)
                put("uom_code", "EA")
                put("price", "1040.00")
            },
        )
        // Legacy table contains obsolete prices for both Rice and Dhal
        val legacyRice = SnapshotRow("price", "p-rice", null, buildJsonObject { put("sku_id", Samples.SKU_RICE); put("unit_price", "990.00") })
        val legacyDhal = SnapshotRow("price", "p-dhal", null, buildJsonObject { put("sku_id", Samples.SKU_DHAL); put("unit_price", "250.00") })

        val state = SnapshotState.EMPTY.apply(
            SnapshotDelta.fromJson(Samples.snapshotAnswer(10, listOf(skuRice, skuDhal, riceLine, legacyRice, legacyDhal))),
            LocalDate(2026, 10, 1),
        )

        val catalogue = Catalogue(state, businessDate = LocalDate(2026, 10, 1))
        // Rice resolves to M3 authoritative price
        assertEquals(Money.parse("1040.00"), catalogue.byBarcode("4790000000011")?.price)
        // Sugar has no M3 price; must NOT silently fall back to legacy price 250.00
        assertNull(catalogue.byBarcode("4790000000028")?.price)
    }
}
