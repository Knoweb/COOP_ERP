package lk.coopfed.knoweb.till.desktop.dev

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.parameters
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lk.coopfed.knoweb.till.desktop.DesktopConfig

/**
 * `./gradlew :desktop:devEnrolmentCode` (till/README.md): what the shop's manager does in the back
 * office before a till is enrolled, through the same API the backend's demo till (make
 * demo-till-sale) uses, against the local stack with the demo users:
 *
 * 1. signs in as m101-manager (password demo);
 * 2. registers the till DESKTOP-TRIAL-S01 at Kuliyapitiya town shop when it is not there yet, and
 *    puts it on till position 2 (position 1 is the demo till's);
 * 3. issues a one-time enrolment code;
 * 4. writes (read as m101-buyer) the society's published shelf prices as the till's price book (central's snapshot has
 *    no price table yet) and the device id and code for the enrol form.
 *
 * Settings: COOP_ERP_API (default http://localhost:8080), COOP_ERP_TOKEN_URL (the local Keycloak),
 * COOP_TILL_HOME (the till's data folder), TILL_POSITION_NO (default 2).
 */
fun main() = runBlocking {
    val api = System.getenv("COOP_ERP_API") ?: "http://localhost:8080"
    val tokenUrl = System.getenv("COOP_ERP_TOKEN_URL") ?: "http://localhost:8085/realms/coop/protocol/openid-connect/token"
    val positionNo = System.getenv("TILL_POSITION_NO")?.toInt() ?: 2
    val serial = "DESKTOP-TRIAL-S01"
    val society = "0190f0de-0000-7000-8000-0000000000e3"
    val shop = "0190f0de-0000-7000-8000-000000000132"
    val home = DesktopConfig().home
    val http = HttpClient(Java)
    val json = Json { ignoreUnknownKeys = true }

    suspend fun signIn(user: String): String = json.parseToJsonElement(
        http.submitForm(tokenUrl, parameters {
            append("grant_type", "password")
            append("client_id", "coop-erp-web")
            append("username", user)
            append("password", "demo")
            append("scope", "openid")
        }).bodyAsText(),
    ).jsonObject["access_token"]?.jsonPrimitive?.contentOrNull ?: error("$user could not sign in at $tokenUrl")
    val manager = signIn("m101-manager")
    // The society's buyer may read its price lists; the manager may not (prc.pricelist.view).
    val buyer = signIn("m101-buyer")

    suspend fun call(method: HttpMethod, path: String, body: JsonObject? = null, token: String = manager): JsonElement {
        val answer = http.request("$api$path") {
            this.method = method
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Scope-Entity", society)
            if (method == HttpMethod.Post) header("Idempotency-Key", UUID.randomUUID().toString())
            contentType(ContentType.Application.Json)
            if (body != null) setBody(body.toString())
        }
        val text = answer.bodyAsText()
        check(answer.status.value in 200..299) { "$method $path answered ${answer.status}: $text" }
        return if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text)
    }

    val position = call(HttpMethod.Get, "/v1/party/locations/$shop/positions").jsonArray.map { it.jsonObject }
        .firstOrNull { it["positionNo"]?.jsonPrimitive?.intOrNull == positionNo }
        ?.get("tillPositionId")?.jsonPrimitive?.content
        ?: error("Kuliyapitiya town shop has no till position $positionNo: run make demo-data first")

    var device = call(HttpMethod.Get, "/v1/party/devices?locationId=$shop").jsonArray.map { it.jsonObject }
        .firstOrNull { it.str("hardwareSerial") == serial && it.str("status") != "RETIRED" }
    if (device == null) {
        device = call(HttpMethod.Post, "/v1/party/devices", buildJsonObject {
            put("hardwareSerial", serial)
            put("deviceKind", "POS_TERMINAL")
            put("locationId", shop)
            put("appVersion", "0.1.0")
            put("stagingReference", "DESKTOP-TRIAL")
        }).jsonObject
        println("Registered $serial at Kuliyapitiya town shop")
    }
    val deviceId = device.str("deviceId")!!
    if (device.str("tillPositionId") == null) {
        call(HttpMethod.Post, "/v1/party/devices/$deviceId/assign", buildJsonObject {
            put("tillPositionId", position)
            put("reasonCode", "OPENING")
        })
        println("Put $serial on till position $positionNo")
    }
    val code = call(HttpMethod.Post, "/v1/sync/devices/$deviceId/enrolment-codes").jsonObject.str("code")!!

    // The society's published shelf price list for its shops, as the trial's price book.
    val prices = mutableListOf<String>()
    val lists = call(HttpMethod.Get, "/v1/pricing/lists?kind=RETAIL&status=PUBLISHED", token = buyer).jsonArray.map { it.jsonObject }
    for (list in lists) {
        val id = list.str("listId") ?: list.str("priceListId") ?: continue
        val detail = call(HttpMethod.Get, "/v1/pricing/lists/$id", token = buyer).jsonObject
        for (line in (detail["lines"] as? JsonArray).orEmpty().map { it.jsonObject }) {
            if ((line["tierFromQty"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0) == 0.0) {
                prices += "${line.str("skuId")},${line.str("price")}"
            }
        }
    }
    var source = "the society's published shelf prices"
    if (prices.isEmpty()) {
        // No shelf list the buyer can read on this stack: the demo catalogue's printed MRP (or the
        // distributor price) by barcode, the prices make demo-till-sale charges.
        prices += demoCataloguePrices()
        source = "the demo catalogue's printed MRPs (no published shelf list was readable)"
    }
    Files.createDirectories(home)
    Files.writeString(home.resolve("prices.csv"), (listOf("# sku_id or barcode,price: $source (trial price book)") + prices.distinct()).joinToString("\n"))
    Files.writeString(
        home.resolve("enrol-prefill.properties"),
        "server=$api\ndevice_id=$deviceId\ncode=$code\nhardware_serial=$serial\n",
    )
    println("Device id:        $deviceId")
    println("Enrolment code:   $code   (one use; valid until the time the back office shows)")
    println("Price book:       ${prices.distinct().size} prices -> ${home.resolve("prices.csv")}")
    println("The desktop till's enrol form is filled in from ${home.resolve("enrol-prefill.properties")}")
    http.close()
}

private fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

/**
 * "barcode,price" from backend/app/src/main/resources/demo/catalogue.psv, by the demo loader's
 * rules (DemoCatalogue): the n-th item's barcode is the EAN-13 of 4790000 and n in five digits;
 * goods sold by weight have none.
 */
private fun demoCataloguePrices(): List<String> {
    val file = listOf("../backend/app/src/main/resources/demo/catalogue.psv", "../../backend/app/src/main/resources/demo/catalogue.psv")
        .map { java.nio.file.Paths.get(it) }.firstOrNull { Files.exists(it) } ?: return emptyList()
    var lineNo = 0
    return Files.readAllLines(file).filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
        lineNo++
        val f = line.split('|')
        if (f.size != 19 || f[4].trim().equals("Y", ignoreCase = true)) return@mapNotNull null
        val price = f[17].trim().ifEmpty { f[12].trim() }.ifEmpty { return@mapNotNull null }
        "${ean13("4790000" + lineNo.toString().padStart(5, '0'))},$price"
    }
}

private fun ean13(twelve: String): String {
    val sum = twelve.mapIndexed { i, c -> (c - '0') * (if (i % 2 == 0) 1 else 3) }.sum()
    return twelve + (10 - sum % 10) % 10
}
