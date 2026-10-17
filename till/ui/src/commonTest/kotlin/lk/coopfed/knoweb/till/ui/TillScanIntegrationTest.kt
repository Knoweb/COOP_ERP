package lk.coopfed.knoweb.till.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import lk.coopfed.knoweb.till.core.TillService
import lk.coopfed.knoweb.till.core.model.Anomaly
import lk.coopfed.knoweb.till.core.model.CashMovementRecord
import lk.coopfed.knoweb.till.core.model.DeviceIdentity
import lk.coopfed.knoweb.till.core.model.IssuedReceipt
import lk.coopfed.knoweb.till.core.model.OutboxEntry
import lk.coopfed.knoweb.till.core.model.SessionRecord
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.money.Qty
import lk.coopfed.knoweb.till.core.port.Central
import lk.coopfed.knoweb.till.core.port.PinVerifier
import lk.coopfed.knoweb.till.core.port.SignatureVerifier
import lk.coopfed.knoweb.till.core.port.TillClock
import lk.coopfed.knoweb.till.core.port.TillStore
import lk.coopfed.knoweb.till.core.port.UploadAnswer
import lk.coopfed.knoweb.till.core.receipt.MonoBitmap
import lk.coopfed.knoweb.till.core.snapshot.SnapshotRow
import lk.coopfed.knoweb.till.core.snapshot.SnapshotState
import lk.coopfed.knoweb.till.core.snapshot.SnapshotVerifier
import lk.coopfed.knoweb.till.peripherals.SimulatorPrinter

class TillScanIntegrationTest {

    private fun makeSku(
        id: String,
        skuCode: String,
        name: String,
        barcode: String,
        soldByWeight: Boolean = false,
    ): SnapshotRow = SnapshotRow(
        "sku", id, null,
        buildJsonObject {
            put("sku_code", skuCode)
            put("status", "ACTIVE")
            put("short_name_en", name)
            put("base_uom_code", if (soldByWeight) "KG" else "EA")
            put("sold_by_weight", soldByWeight)
            putJsonArray("barcodes") {
                add(buildJsonObject { put("barcode", barcode) })
            }
        },
    )

    private fun controllerWith(vararg skus: SnapshotRow): TillController = runBlocking {
        val live = mapOf("sku" to skus.associateBy { it.rowId })
        val snapshot = SnapshotState(1, live, emptyList())
        val localPrices = mapOf(
            "sku-rice" to Money.parse("150.00"),
            "sku-veg" to Money.parse("200.00"),
            "sku-dhal" to Money.parse("80.00"),
        )

        val dummyStore = object : TillStore {
            override fun <T> transaction(block: () -> T): T = block()
            override fun setting(key: String): String? = null
            override fun putSetting(key: String, value: String) {}
            override fun removeSetting(key: String) {}
            override fun loadSnapshot(): SnapshotState = snapshot
            override fun saveSnapshot(state: SnapshotState) {}
            override fun appendOutbox(entry: OutboxEntry) {}
            override fun pendingOutbox(limit: Int): List<OutboxEntry> = emptyList()
            override fun pendingCount(): Long = 0L
            override fun acknowledgeUpTo(deviceSeq: Long, atMillis: Long) {}
            override fun purgeAcknowledged(beforeMillis: Long): Long = 0L
            override fun outboxHolds(deviceSeq: Long): Boolean = false
            override fun unacknowledgeFrom(deviceSeq: Long) {}
            override fun maxOutboxSeq(): Long? = null
            override fun saveSession(session: SessionRecord) {}
            override fun openSession(): SessionRecord? = null
            override fun session(sessionId: String): SessionRecord? = null
            override fun saveCashMovement(movement: CashMovementRecord) {}
            override fun cashMovementsOfSession(sessionId: String): List<CashMovementRecord> = emptyList()
            override fun saveReceipt(receipt: IssuedReceipt) {}
            override fun receiptsOfSession(sessionId: String): List<IssuedReceipt> = emptyList()
            override fun maxReceiptNumber(): Long? = null
            override fun addAnomaly(anomaly: Anomaly) {}
            override fun anomalies(limit: Int): List<Anomaly> = emptyList()
            override fun unseenAnomalyCount(): Long = 0L
            override fun markAnomaliesSeen() {}
            override fun quarantinedAmong(deviceSeqs: Collection<Long>): Long = 0L
        }

        val dummyCentral = object : Central {
            override fun identify(device: DeviceIdentity) {}
            override suspend fun enrol(serverUrl: String, deviceId: String, code: String, hardwareSerial: String, appVersion: String): JsonObject = JsonObject(emptyMap())
            override suspend fun snapshot(since: Long): JsonObject = JsonObject(emptyMap())
            override suspend fun upload(batch: JsonObject): UploadAnswer = UploadAnswer.Acknowledged(0, 0, null)
            override suspend fun heartbeat(report: JsonObject): JsonObject = JsonObject(emptyMap())
        }

        val service = TillService(
            store = dummyStore,
            central = dummyCentral,
            verifier = SnapshotVerifier(SignatureVerifier { _, _, _ -> true }),
            pins = PinVerifier { _, _ -> true },
            clock = object : TillClock {
                override fun now() = kotlin.time.Instant.fromEpochMilliseconds(1000)
                override val zone = TimeZone.of("Asia/Colombo")
                override fun elapsedMillis(): Long = 1000
            },
            ids = object : lk.coopfed.knoweb.till.core.id.IdGenerator {
                private var seq = 0L
                override fun next(): String = "id-${++seq}"
            },
            appVersion = "0.1.0",
            localPrices = localPrices,
        )

        service.start()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        TillController(
            service = service,
            printer = SimulatorPrinter(),
            rasteriser = { _, _ -> MonoBitmap(1, 1) },
            scope = scope,
            zone = TimeZone.of("Asia/Colombo"),
        )
    }

    @Test
    fun scanEan13AddsSingleQuantityToBasket() {
        val c = controllerWith(
            makeSku("sku-rice", "RICE", "Rice 5kg", "4791234567891"),
        )

        val searchResults = c.scan("4791234567891", atMillis = 1000)
        assertTrue(searchResults.isEmpty(), "Scan match should not return search results")
        assertEquals(1, c.lines.size)
        val line = c.lines[0]
        assertEquals("sku-rice", line.item.skuId)
        assertEquals(Qty.ONE, line.qty)
        assertEquals(Money.parse("150.00"), line.unitPrice)
        assertEquals(Money.parse("150.00"), line.total)
    }

    @Test
    fun scanWeighedLabelAddsLineWithParsedWeightAndCalculatedTotal() {
        val c = controllerWith(
            makeSku("sku-veg", "123456", "Fresh Carrots", "2012345600000", soldByWeight = true),
        )

        // Weighed barcode: 2012345608504 (prefix 20, code 123456, 850 grams = 0.850 kg, check digit 4)
        val searchResults = c.scan("2012345608504", atMillis = 1000)
        assertTrue(searchResults.isEmpty())
        assertEquals(1, c.lines.size)
        val line = c.lines[0]
        assertEquals("sku-veg", line.item.skuId)
        assertEquals(Qty(850L), line.qty)
        assertEquals(Money.parse("200.00"), line.unitPrice)
        // 0.850 kg * 200.00 LKR = 170.00 LKR
        assertEquals(Money.parse("170.00"), line.total)
    }

    @Test
    fun scanGs1DataMatrixWithWeightQuantity() {
        val c = controllerWith(
            makeSku("sku-rice", "RICE", "Basmati Rice", "4791234567891", soldByWeight = true),
        )

        // GS1 DataMatrix: (01)04791234567891(10)LOT123(17)261231(3103)001500
        // Net weight 1500 grams = 1.500 kg
        val searchResults = c.scan("(01)04791234567891(10)LOT123(17)261231(3103)001500", atMillis = 1000)
        assertTrue(searchResults.isEmpty())
        assertEquals(1, c.lines.size)
        val line = c.lines[0]
        assertEquals("sku-rice", line.item.skuId)
        assertEquals(Qty(1500L), line.qty)
        // 1.500 * 150.00 = 225.00
        assertEquals(Money.parse("225.00"), line.total)
    }

    @Test
    fun scanWeightBarcodeForUnitOnlyItemRejected() {
        val c = controllerWith(
            makeSku("sku-rice", "RICE", "Rice 5kg Pack", "4791234567891", soldByWeight = false),
        )

        // Scan barcode with embedded weight 1.500 kg on unit-only product
        val results = c.scan("(01)04791234567891(10)LOT123(17)261231(3103)001500", atMillis = 1000)
        assertTrue(results.isEmpty())
        assertEquals(0, c.lines.size, "Weight barcode scanned for unit-only product must not add a basket line")
        assertEquals("Cannot apply weight to unit product \"Rice 5kg Pack\"", c.message)
    }

    @Test
    fun scanStandardBarcodeForWeighedItemPreservesExistingWorkflow() {
        val c = controllerWith(
            makeSku("sku-veg", "123456", "Fresh Carrots", "4791234567891", soldByWeight = true),
        )

        // Standard barcode on weighed product has no embedded weight: adds 1.000 kg preserving manual workflow
        val results = c.scan("4791234567891", atMillis = 1000)
        assertTrue(results.isEmpty())
        assertEquals(1, c.lines.size, "Standard barcode on weighed product must add 1 unit to allow manual workflow")
        assertEquals(Qty.ONE, c.lines[0].qty)
        assertEquals(Money.parse("200.00"), c.lines[0].total)
    }

    @Test
    fun scanDualMarkedPackAddsOnlyOneLine() {
        val c = controllerWith(
            makeSku("sku-rice", "RICE", "Rice 5kg", "4791234567891"),
        )

        // Read 1: 1D EAN-13
        c.scan("4791234567891", atMillis = 1000)
        assertEquals(1, c.lines.size)

        // Read 2: 2D DataMatrix on same pack 150ms later
        c.scan("(01)04791234567891(10)LOT1", atMillis = 1150)
        assertEquals(1, c.lines.size, "Dual-marked pack read within 700ms must not add a second line")
    }

    @Test
    fun scanLegitimateRepeatAfter700msIncrementsQuantity() {
        val c = controllerWith(
            makeSku("sku-rice", "RICE", "Rice 5kg", "4791234567891"),
        )

        // Customer buying 2 boxes of rice
        c.scan("4791234567891", atMillis = 1000)
        assertEquals(1, c.lines.size)
        assertEquals(Qty(1000L), c.lines[0].qty)

        // Second box scanned 1200ms later
        c.scan("4791234567891", atMillis = 2200)
        assertEquals(1, c.lines.size)
        assertEquals(Qty(2000L), c.lines[0].qty, "Second item scanned after 700ms must increment quantity")
    }

    @Test
    fun scanTwoIdenticalProductsRapidlyWithin700msIncrementsQuantity() {
        val c = controllerWith(
            makeSku("sku-rice", "RICE", "Rice 5kg", "4791234567891"),
        )

        // Cashier scans first box of rice
        c.scan("4791234567891", atMillis = 1000)
        assertEquals(1, c.lines.size)
        assertEquals(Qty(1000L), c.lines[0].qty)

        // Cashier scans second identical box 300ms later (within 700ms window)
        c.scan("4791234567891", atMillis = 1300)
        assertEquals(1, c.lines.size)
        assertEquals(Qty(2000L), c.lines[0].qty, "Second identical item scanned rapidly (< 700ms) must increment quantity")
    }

    @Test
    fun scanTwoDifferentProductsRapidlyWithin700msAddsBothLines() {
        val c = controllerWith(
            makeSku("sku-rice", "RICE", "Rice 5kg", "4791234567891"),
            makeSku("sku-dhal", "DHAL", "Dhal 1kg", "4791234567884"),
        )

        // First item scanned
        c.scan("4791234567891", atMillis = 1000)
        assertEquals(1, c.lines.size)

        // Second different item scanned 150ms later
        c.scan("4791234567884", atMillis = 1150)
        assertEquals(2, c.lines.size, "Two different items scanned rapidly must both be added to basket")
    }

    @Test
    fun invalidCheckDigitNeverResolvesEvenIfCatalogueContainsMatchingBarcode() {
        val c = controllerWith(
            makeSku("sku-rice", "RICE", "Rice 5kg", "4791234567890"),
        )

        // Invalid check digit 0 instead of 1
        val results = c.scan("4791234567890", atMillis = 1000)
        assertEquals(0, c.lines.size, "Invalid check digit barcode must not add a basket line even if catalogue has row")
        assertTrue(results.isEmpty())
        assertEquals("Invalid GTIN check digit in barcode \"4791234567890\"", c.message)
    }

    @Test
    fun scanLegacyCustomBarcodeAddsToBasket() {
        val c = controllerWith(
            makeSku("sku-rice", "RICE", "Rice 5kg", "PROD-1234"),
        )

        val results = c.scan("PROD-1234", atMillis = 1000)
        assertTrue(results.isEmpty())
        assertEquals(1, c.lines.size, "Legacy non-GTIN barcode must resolve and add to basket")
        assertEquals("sku-rice", c.lines[0].item.skuId)
    }

    @Test
    fun manualQueryFallsThroughToCatalogueSearch() {
        val c = controllerWith(
            makeSku("sku-rice", "RICE", "Rice 5kg", "4791234567891"),
        )

        val results = c.scan("Rice", atMillis = 1000)
        assertEquals(1, results.size, "Manual search query must return catalogue search results")
        assertEquals(0, c.lines.size, "Manual search query must not add line directly")
    }
}
