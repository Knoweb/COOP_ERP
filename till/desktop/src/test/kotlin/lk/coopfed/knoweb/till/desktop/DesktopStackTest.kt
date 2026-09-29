package lk.coopfed.knoweb.till.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.receipt.MonoBitmap
import lk.coopfed.knoweb.till.core.receipt.ReceiptLayouts
import lk.coopfed.knoweb.till.core.receipt.SlipHeader
import lk.coopfed.knoweb.till.core.sale.Basket
import lk.coopfed.knoweb.till.peripherals.PrintJob
import lk.coopfed.knoweb.till.peripherals.jvm.PreviewFolderPrinter
import lk.coopfed.knoweb.till.render.SkiaReceiptRasteriser

/**
 * The desktop till against the running local stack, end to end (opt-in; never in CI by default):
 *
 *     ./gradlew :desktop:devEnrolmentCode              # once: registers the till, issues a code
 *     ./gradlew :desktop:test -Ptill.stack=true --tests '*DesktopStackTest*'
 *
 * with COOP_TILL_HOME set the same for both. It enrols (when the till is not enrolled yet), takes
 * and verifies the signed snapshot, opens a session, sells two items by barcode for cash, closes
 * the session, prints the receipt and the Z-report to the preview folder, uploads through the sync
 * contract, and checks that central holds the receipt unflagged (its content hash matched).
 */
class DesktopStackTest {

    @Test
    fun sellPrintCloseAndUploadAgainstTheLocalStack() = runBlocking {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            System.getProperty("till.stack") == "true",
            "opt-in: run with -Ptill.stack=true against the local stack",
        )
        DesktopTill().use { till ->
            val service = till.service
            service.start()
            if (!service.isEnrolled) {
                val prefill = Properties().apply { Files.newBufferedReader(till.config.home.resolve("enrol-prefill.properties")).use { load(it) } }
                service.enrol(prefill.getProperty("server"), prefill.getProperty("device_id"), prefill.getProperty("code"), prefill.getProperty("hardware_serial"))
            }
            val version = service.refreshSnapshot()
            val catalogue = service.catalogue
            println("snapshot v$version: ${catalogue.items.size} items, ${catalogue.operators.size} operators, shop ${catalogue.shop?.name?.en}")
            assertTrue(catalogue.items.isNotEmpty(), "the snapshot carries the shop's items")

            service.signInTrialCashier()
            service.currentSession()?.let { service.closeSession(Money.parse("0")) }
            service.openSession(Money.parse("2000.00"))
            val priced = catalogue.items.filter { it.price != null && it.barcodes.isNotEmpty() }.take(2)
            assertEquals(2, priced.size, "two items with a barcode and a price (run devEnrolmentCode for the price book)")
            val basket = Basket()
            priced.forEach { item -> basket.add(catalogue.byBarcode(item.barcodes.first())!!, item.price!!) }
            val receipt = service.sellForCash(basket, Money.parse("10000.00"))
            val z = service.closeSession(Money.parse("2000.00") + receipt.gross)
            assertEquals(Money.ZERO, z.variance)

            val rasteriser = SkiaReceiptRasteriser()
            val printer = PreviewFolderPrinter(till.config.previewFolder)
            val header = SlipHeader(catalogue.shop?.name?.get(service.shopLanguage) ?: "", catalogue.shop?.code ?: "", service.device?.positionNo?.toString() ?: "")
            printer.print(PrintJob(receipt.numberDisplay, rasteriser.rasterise(ReceiptLayouts.receipt(receipt, header, service.shopLanguage, till.zone), MonoBitmap.DOTS_80MM), true))
            printer.print(PrintJob("Z-${z.session.businessDate}", rasteriser.rasterise(ReceiptLayouts.zReport(z, header, service.shopLanguage, till.zone), MonoBitmap.DOTS_80MM), false))
            assertEquals(0, rasteriser.lastUnresolvedGlyphs)
            assertTrue(till.config.previewFolder.listDirectoryEntries("*.png").isNotEmpty())

            service.syncOnce()
            assertEquals(0L, service.status.value.pendingFacts, "every fact acknowledged: ${service.status.value.message}")
            println("sold ${receipt.numberDisplay} for LKR ${receipt.gross.display()} and uploaded")

            // Central applies the receipt through the relay; look for it as the shop's manager would.
            val found = centralReceipt(receipt.documentId, service.device!!.locationId)
            println("central: $found")
            assertEquals(true, found != null, "central holds receipt ${receipt.numberDisplay}")
            // SESSION_UNKNOWN alone is central's race, not the till's: M6 applies receipts and sessions in
            // two consumers, so a receipt uploaded in the same batch as its session can be applied
            // first (seen 29 Sep 2026; raised in the pull request). Any other flag is the till's fault.
            val flags = found!!["flags"]!!.jsonArray.map { it.jsonPrimitive.content } - "SESSION_UNKNOWN"
            assertEquals(emptyList(), flags, "central flagged the receipt: $found")
        }
    }

    private suspend fun centralReceipt(documentId: String, locationId: String): JsonObject? {
        val api = System.getenv("COOP_ERP_API") ?: "http://localhost:8080"
        val tokenUrl = System.getenv("COOP_ERP_TOKEN_URL") ?: "http://localhost:8085/realms/coop/protocol/openid-connect/token"
        HttpClient(Java).use { http ->
            val token = Json.parseToJsonElement(
                http.submitForm(tokenUrl, parameters {
                    append("grant_type", "password"); append("client_id", "coop-erp-web")
                    append("username", "m101-manager"); append("password", "demo"); append("scope", "openid")
                }).bodyAsText(),
            ).jsonObject.getValue("access_token").jsonPrimitive.content
            repeat(30) {
                val page = http.get("$api/v1/pos/receipts?locationId=$locationId") {
                    header("Authorization", "Bearer $token")
                    header("X-Scope-Entity", "0190f0de-0000-7000-8000-0000000000e3")
                }.bodyAsText()
                val element = Json.parseToJsonElement(page)
                val items = (element as? kotlinx.serialization.json.JsonArray) ?: element.jsonObject["items"]?.jsonArray
                items?.map { it.jsonObject }?.firstOrNull { it["documentId"]?.jsonPrimitive?.content == documentId }?.let { return it }
                delay(1000)
            }
        }
        return null
    }

}
