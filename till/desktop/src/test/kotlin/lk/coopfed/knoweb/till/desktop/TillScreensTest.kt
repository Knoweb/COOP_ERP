package lk.coopfed.knoweb.till.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import lk.coopfed.knoweb.till.core.TillService
import lk.coopfed.knoweb.till.core.id.UuidV7
import lk.coopfed.knoweb.till.core.model.DeviceIdentity
import lk.coopfed.knoweb.till.core.port.Central
import lk.coopfed.knoweb.till.core.port.TillClock
import lk.coopfed.knoweb.till.core.port.UploadAnswer
import lk.coopfed.knoweb.till.core.snapshot.SnapshotRow
import lk.coopfed.knoweb.till.core.snapshot.SnapshotTableDelta
import lk.coopfed.knoweb.till.core.snapshot.SnapshotVerifier
import lk.coopfed.knoweb.till.db.EncryptedJvmDatabase
import lk.coopfed.knoweb.till.db.SqlTillStore
import lk.coopfed.knoweb.till.peripherals.jvm.PreviewFolderPrinter
import lk.coopfed.knoweb.till.render.SkiaReceiptRasteriser
import lk.coopfed.knoweb.till.sync.Argon2PinVerifier
import lk.coopfed.knoweb.till.sync.JdkEd25519Verifier
import lk.coopfed.knoweb.till.ui.EnrolDefaults
import lk.coopfed.knoweb.till.ui.TillApp
import lk.coopfed.knoweb.till.ui.TillController

/**
 * The shared screens in the desktop window, driven as a cashier would with the keyboard: enrol,
 * sign in, open the session, scan two items (Enter), take cash (F2, Enter), close the session with
 * the blind count, and see the Z-report. Central is a stand-in that signs its snapshot with a real
 * Ed25519 key; the database is the real encrypted one. Screenshots of each step go to
 * build/ui-screens.
 */
@OptIn(ExperimentalTestApi::class)
class TillScreensTest {

    private val home = Files.createTempDirectory("till-ui")
    private val shots = File("build/ui-screens").apply { mkdirs() }
    private val central = SigningCentral()

    @Test
    fun aCashierSellsAndClosesTheSessionWithTheKeyboard() = runComposeUiTest {
        val driver = EncryptedJvmDatabase.open(home.resolve("till.db"), "0f".repeat(32))
        val zone = TimeZone.of("Asia/Colombo")
        val ids = UuidV7({ System.currentTimeMillis() })
        val service = TillService(
            SqlTillStore(driver), central, SnapshotVerifier(JdkEd25519Verifier), Argon2PinVerifier,
            object : TillClock {
                override fun now() = Clock.System.now()
                override val zone = zone
            },
            ids, "0.1.0",
            // The trial's explicit opt-in (TWK-04): the stand-in central sends no operator.
            trialCashierAllowed = true,
        )
        val controller = TillController(
            service, PreviewFolderPrinter(home.resolve("print")), SkiaReceiptRasteriser(),
            CoroutineScope(SupervisorJob() + Dispatchers.Default), zone, syncEveryMillis = 3_600_000,
        )
        setContent {
            TillApp(controller, EnrolDefaults(deviceId = SigningCentral.DEVICE, code = "TESTCODE"))
        }

        waitForText("Enrol this till")
        shot("1-enrol")
        onNode(hasText("Enrol and take the snapshot")).performClick()
        waitForText("Continue as trial cashier")
        shot("2-sign-in")
        onNode(hasText("Continue as trial cashier")).performClick()
        waitForText("Open the session")
        onNode(hasText("Open session")).performClick()
        waitForText("Scan or type a barcode or name, then Enter")

        val scan = onNode(hasSetTextAction() and hasText("Scan or type a barcode or name, then Enter"))
        scan.performTextInput("4790000000013")
        scan.performKeyInput { pressKey(Key.Enter) }
        scan.performTextInput("4790000000020")
        scan.performKeyInput { pressKey(Key.Enter) }
        waitForText("LKR 2,440.00")
        shot("3-basket")

        scan.performKeyInput { pressKey(Key.F2) }
        waitForText("Cash: LKR 2,440.00")
        onNode(hasSetTextAction() and hasText("Cash given (LKR)")).performTextReplacement("5000")
        shot("4-cash", dialog = true)
        onNode(hasText("Complete (Enter)")).performClick()
        waitForText("change LKR 2,560.00", substring = true)
        waitUntil(timeoutMillis = 10_000) { runCatching { home.resolve("print").listDirectoryEntries("*.png").isNotEmpty() }.getOrDefault(false) }
        shot("5-sold")

        onNode(hasText("Close the session  (F10)")).performClick()
        waitForText("Counted cash (LKR)")
        onNode(hasSetTextAction() and hasText("Counted cash (LKR)")).performTextInput("4440")
        onNode(hasText("Close and print the Z-report")).performClick()
        waitForText("Z-report", substring = true)
        shot("6-z-report")

        assertEquals(1, controller.lastReceipt?.number)
        assertEquals("0.00", controller.lastZReport?.variance?.plain())
        waitUntil(timeoutMillis = 10_000) { home.resolve("print").listDirectoryEntries("*.png").size == 2 }
        assertTrue(home.resolve("print").listDirectoryEntries("*.bin").size == 2)
        driver.close()
    }

    private fun SemanticsNodeInteractionsProvider.shot(name: String, dialog: Boolean = false) {
        val roots = onAllNodes(isRoot())
        val root = if (dialog) roots.onLast() else roots.onFirst()
        ImageIO.write(root.captureToImage().toAwtImage(), "png", File(shots, "$name.png"))
    }

    private fun androidx.compose.ui.test.ComposeUiTest.waitForText(text: String, substring: Boolean = false) {
        waitUntil(timeoutMillis = 15_000) { onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
    }
}

/** Central for the screen test: enrols, and serves a two-item snapshot signed with a fresh Ed25519 key. */
private class SigningCentral : Central {
    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    override suspend fun enrol(serverUrl: String, deviceId: String, code: String, hardwareSerial: String, appVersion: String) = buildJsonObject {
        put("device_id", DEVICE)
        put("owner_entity_id", "0190f0de-0000-7000-8000-0000000000e3")
        put("location_id", LOCATION)
        put("till_position_id", "0190f0de-0000-7000-8000-0000000d0002")
        put("position_no", 2)
        put("primary_till", false)
        putJsonObject("credential") { put("client_id", "device-test"); put("client_secret", "not-a-secret"); put("token_endpoint", "http://localhost/token") }
        put("next_device_seq", 1)
        putJsonArray("series") {
            add(buildJsonObject {
                put("series_id", "0190f0de-0000-7000-8000-0000000e0001"); put("doc_type_code", "RCT")
                put("scope", "TILL_POSITION"); put("prefix", "M101-S01-T2-RCT"); put("next_number", 1)
            })
        }
        putJsonObject("snapshot") { put("version", 0); put("full_snapshot_required", true) }
        putJsonObject("signing_key") {
            put("key_id", "test"); put("algorithm", "Ed25519"); put("public_key", Base64.getEncoder().encodeToString(keys.public.encoded))
        }
    }

    override fun identify(device: DeviceIdentity) {}

    override suspend fun snapshot(since: Long): JsonObject {
        val rows = listOf(
            sku("0190f0de-0000-7000-8000-0000000a0001", "Samba rice 5 kg", "සම්බා සහල් 5 kg", "4790000000013", "1290.00"),
            sku("0190f0de-0000-7000-8000-0000000a0002", "Nadu rice 5 kg", "නාඩු සහල් 5 kg", "4790000000020", "1150.00"),
        )
        val prices = rows.map { (sku, price) -> SnapshotRow("price", "p" + sku.rowId.drop(1), null, buildJsonObject { put("sku_id", sku.rowId); put("unit_price", price) }) }
        val tables = mapOf("sku" to rows.map { it.first }, "price" to prices)
        val manifest = buildJsonObject {
            put("full", true); put("location_id", LOCATION); put("since", 0)
            putJsonObject("tables") {
                for ((name, list) in tables.toSortedMap()) putJsonObject(name) {
                    put("sha256", SnapshotVerifier.tableHash(SnapshotTableDelta(list, emptyList()))); put("tombstones", 0); put("upserts", list.size)
                }
            }
            put("version", 1)
        }.toString()
        val signature = Signature.getInstance("Ed25519").run { initSign(keys.private); update(manifest.toByteArray()); Base64.getEncoder().encodeToString(sign()) }
        return buildJsonObject {
            put("location_id", LOCATION); put("version", 1); put("full_snapshot_required", true); put("urgent", false)
            putJsonObject("tables") {
                for ((name, list) in tables) putJsonObject(name) {
                    put("upserts", JsonArray(list.map { buildJsonObject { put("row_id", it.rowId); put("data", it.data!!) } }))
                    put("tombstones", buildJsonArray { })
                }
            }
            put("manifest", manifest); put("signature", signature); put("key_id", "test")
        }
    }

    override suspend fun upload(batch: JsonObject): UploadAnswer {
        val first = batch.getValue("first_seq").jsonPrimitive.long
        return UploadAnswer.Acknowledged(first + batch.getValue("events").jsonArray.size - 1, 1, 0)
    }

    override suspend fun heartbeat(report: JsonObject) = buildJsonObject { put("snapshot_version", 1) }

    private fun sku(id: String, en: String, si: String, barcode: String, price: String) = SnapshotRow(
        "sku", id, null,
        buildJsonObject {
            put("sku_code", en.take(4).uppercase()); put("status", "SHARED"); put("short_name_en", en); put("short_name_si", si)
            put("base_uom_code", "EA"); put("sold_by_weight", false)
            putJsonArray("barcodes") { add(buildJsonObject { put("barcode", barcode) }) }
        },
    ) to price

    companion object {
        const val DEVICE = "0190f0de-0000-7000-8000-0000000c0001"
        const val LOCATION = "0190f0de-0000-7000-8000-000000000132"
    }
}
