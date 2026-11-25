package lk.coopfed.knoweb.till.core.shop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lk.coopfed.knoweb.till.core.CountingIds
import lk.coopfed.knoweb.till.core.FixedClock
import lk.coopfed.knoweb.till.core.InMemoryStore
import lk.coopfed.knoweb.till.core.TillFixture
import lk.coopfed.knoweb.till.core.TillRefusal
import lk.coopfed.knoweb.till.core.TillService
import lk.coopfed.knoweb.till.core.model.ClockReading
import lk.coopfed.knoweb.till.core.model.DeviceIdentity
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.money.Qty
import lk.coopfed.knoweb.till.core.sale.ContentHash

class ShopBundleBuilderTest {

    private val device = DeviceIdentity(
        serverUrl = "https://central.coop.test",
        deviceId = "0190f0de-0000-7000-8000-000000000001",
        hardwareSerial = "SERIAL-001",
        ownerEntityId = "0190f0de-0000-7000-8000-000000000010",
        locationId = "0190f0de-0000-7000-8000-000000000100",
        tillPositionId = "0190f0de-0000-7000-8000-000000001000",
        positionNo = 1,
        primaryTill = true,
        clientId = "client-1",
        clientSecret = "secret-1",
        tokenEndpoint = "https://central.coop.test/token",
        signingKeyId = "key-1",
        signingPublicKey = "pub-key",
        receiptSeriesId = "0190f0de-0000-7000-8000-000000010000",
        receiptPrefix = "M101-S01-T1-RCT",
    )

    private val operatorUserId = "0190f0de-0000-7000-8000-000000000099"
    private val businessDate = LocalDate(2026, 11, 25)
    private val zone = TimeZone.of("Asia/Colombo")
    private val clockInstant = Instant.parse("2026-11-25T08:30:00Z")
    private val clockReading = ClockReading(
        occurredAt = clockInstant,
        rawInstant = clockInstant,
        clockOffsetMs = null,
        offlineTimed = false,
    )
    private val ids = CountingIds("0190f0de-0000-7000-8000-")

    // =========================================================================
    // 1. GRN Bundle Tests
    // =========================================================================

    @Test
    fun grnBundleBuildsConformingPayloadAndCanonicalHash() {
        val input = GrnOperationInput(
            supplierId = "0190f0de-0000-7000-8000-000000000555",
            deliveryDocumentId = "0190f0de-0000-7000-8000-000000000444",
            lines = listOf(
                GrnLineInput(
                    skuId = "0190f0de-0000-7000-8000-000000000101",
                    batchId = "0190f0de-0000-7000-8000-000000000201",
                    batchNo = "B-2026-11",
                    uomCode = "EA",
                    receivedQty = Qty.of(50),
                    damagedQty = Qty.of(2),
                    expectedQty = Qty.of(50),
                    unitCost = Money.parse("150.00"),
                ),
            ),
            attachments = listOf(
                ShopAttachmentReference(
                    attachmentId = "0190f0de-0000-7000-8000-000000000888",
                    objectKey = "grn/damage1.jpg",
                    contentHash = "a".repeat(64),
                ),
            ),
        )

        val bundle = ShopBundleBuilder.buildGrnBundle(
            device = device,
            operatorUserId = operatorUserId,
            businessDate = businessDate,
            clockReading = clockReading,
            zone = zone,
            input = input,
            docNumber = 42L,
            deviceSeq = 100L,
            ids = ids,
        )

        assertEquals("grn.confirmed.v1", bundle.eventType)
        assertEquals("GRN", bundle.document.docTypeCode)
        assertEquals(42L, bundle.document.docNumber)
        assertEquals("M101-S01-T1-GRN-42", bundle.document.docNumberDisplay)
        assertEquals("CONFIRMED", bundle.document.status)
        assertEquals(100L, bundle.document.deviceSeq)
        assertEquals(Money.parse("7500.00"), bundle.document.netAmount)

        val eventJson = Json.parseToJsonElement(bundle.outboxEntry.json).jsonObject
        assertEquals("grn.confirmed.v1", eventJson["event_type"]?.jsonPrimitive?.content)
        assertEquals(100L, eventJson["device_seq"]?.jsonPrimitive?.content?.toLong())
        assertEquals(bundle.contentHash, eventJson["content_hash"]?.jsonPrimitive?.content)

        val payload = eventJson["payload"]?.jsonObject
        assertNotNull(payload)
        val docJson = payload["document"]?.jsonObject
        assertNotNull(docJson)
        val linesJson = payload["lines"]?.jsonArray
        assertNotNull(linesJson)

        // Verify content hash recalculated matches
        val expectedHash = ContentHash.of(docJson, linesJson)
        assertEquals(expectedHash, bundle.contentHash)

        // Verify stock movements: 1 GOOD (48) + 1 DAMAGED (2)
        assertEquals(2, bundle.stockMovements.size)
        assertEquals("GOOD", bundle.stockMovements[0].condition)
        assertEquals("48.000", bundle.stockMovements[0].qtyDelta)
        assertEquals("DAMAGED", bundle.stockMovements[1].condition)
        assertEquals("2.000", bundle.stockMovements[1].qtyDelta)

        // Verify state history
        assertEquals(1, bundle.stateHistory.size)
        assertEquals("DRAFT", bundle.stateHistory[0].fromStatus)
        assertEquals("CONFIRMED", bundle.stateHistory[0].toStatus)

        // Verify audit event
        assertEquals(1, bundle.auditEvents.size)
        assertEquals("GRN_CONFIRMED", bundle.auditEvents[0].eventTypeCode)
    }

    @Test
    fun grnRejectsNegativeQuantities() {
        val input = GrnOperationInput(
            lines = listOf(
                GrnLineInput(
                    skuId = "0190f0de-0000-7000-8000-000000000101",
                    uomCode = "EA",
                    receivedQty = Qty.parse("-1.000"),
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            ShopBundleBuilder.buildGrnBundle(device, operatorUserId, businessDate, clockReading, zone, input, 1L, 1L)
        }
    }

    // =========================================================================
    // 2. Stock Count Bundle Tests
    // =========================================================================

    @Test
    fun stockCountBundleBuildsConformingPayloadAndTracksVariances() {
        val input = StockCountOperationInput(
            taskId = "0190f0de-0000-7000-8000-000000000999",
            snapshotVersion = 15L,
            lines = listOf(
                StockCountLineInput(
                    skuId = "0190f0de-0000-7000-8000-000000000101",
                    batchId = "0190f0de-0000-7000-8000-000000000201",
                    uomCode = "EA",
                    countedQty = Qty.of(35),
                    expectedQty = Qty.of(37),
                    condition = "GOOD",
                ),
                StockCountLineInput(
                    skuId = "0190f0de-0000-7000-8000-000000000102",
                    uomCode = "EA",
                    countedQty = Qty.of(0),
                    expectedQty = Qty.of(10),
                    skipped = true,
                    skipReason = "SHELF_UNREACHABLE",
                ),
            ),
        )

        val bundle = ShopBundleBuilder.buildStockCountBundle(
            device = device,
            operatorUserId = operatorUserId,
            businessDate = businessDate,
            clockReading = clockReading,
            zone = zone,
            input = input,
            docNumber = 5L,
            deviceSeq = 101L,
            ids = ids,
        )

        assertEquals("count.recorded.v1", bundle.eventType)
        assertEquals("CNT", bundle.document.docTypeCode)
        assertEquals("RECORDED", bundle.document.status)
        assertEquals(2, bundle.lines.size)
        assertEquals(Qty.of(35), bundle.lines[0].countedQty)
        assertEquals(Qty.of(37), bundle.lines[0].expectedQty)
        assertTrue(bundle.lines[1].skipped)
        assertEquals("SHELF_UNREACHABLE", bundle.lines[1].skipReason)

        val eventJson = Json.parseToJsonElement(bundle.outboxEntry.json).jsonObject
        assertEquals("count.recorded.v1", eventJson["event_type"]?.jsonPrimitive?.content)
        val payload = eventJson["payload"]?.jsonObject
        assertNotNull(payload)
        val countMeta = payload["count"]?.jsonObject
        assertNotNull(countMeta)
        assertEquals("0190f0de-0000-7000-8000-000000000999", countMeta["task_id"]?.jsonPrimitive?.content)
        assertEquals("15", countMeta["snapshot_version"]?.jsonPrimitive?.content)

        // Count does not generate immediate stock movements locally; central evaluates variance
        assertTrue(bundle.stockMovements.isEmpty())
        assertEquals(1, bundle.auditEvents.size)
        assertEquals("STOCK_COUNT_RECORDED", bundle.auditEvents[0].eventTypeCode)
    }

    // =========================================================================
    // 3. Write-off Bundle Tests
    // =========================================================================

    @Test
    fun writeOffBundleBuildsRequestedStatusWithLossCategories() {
        val input = WriteOffOperationInput(
            category = "SPOILAGE",
            reason = "Freezer breakdown overnight",
            witnessUserId = "0190f0de-0000-7000-8000-000000000088",
            lines = listOf(
                WriteOffLineInput(
                    skuId = "0190f0de-0000-7000-8000-000000000101",
                    batchId = "0190f0de-0000-7000-8000-000000000201",
                    uomCode = "KG",
                    qty = Qty.parse("12.500"),
                    unitCost = Money.parse("400.00"),
                    lossCategory = "SPOILAGE",
                ),
            ),
        )

        val bundle = ShopBundleBuilder.buildWriteOffBundle(
            device = device,
            operatorUserId = operatorUserId,
            businessDate = businessDate,
            clockReading = clockReading,
            zone = zone,
            input = input,
            docNumber = 8L,
            deviceSeq = 102L,
            ids = ids,
        )

        assertEquals("writeoff.requested.v1", bundle.eventType)
        assertEquals("WOF", bundle.document.docTypeCode)
        assertEquals("REQUESTED", bundle.document.status)
        assertEquals(Money.parse("5000.00"), bundle.document.netAmount)

        // Negative stock movement written
        assertEquals(1, bundle.stockMovements.size)
        assertEquals("-12.500", bundle.stockMovements[0].qtyDelta)
        assertEquals("WRITE_OFF", bundle.stockMovements[0].movementType)
        assertEquals("SPOILAGE", bundle.stockMovements[0].condition)

        val eventJson = Json.parseToJsonElement(bundle.outboxEntry.json).jsonObject
        val payload = eventJson["payload"]?.jsonObject
        assertNotNull(payload)
        val wofMeta = payload["writeoff"]?.jsonObject
        assertEquals("SPOILAGE", wofMeta?.get("category")?.jsonPrimitive?.content)
        assertEquals("Freezer breakdown overnight", wofMeta?.get("reason")?.jsonPrimitive?.content)
    }

    // =========================================================================
    // 4. Repack Bundle Tests
    // =========================================================================

    @Test
    fun repackBundleConsumesInputLotsAndProducesOutputSku() {
        val input = RepackOperationInput(
            recipeId = "0190f0de-0000-7000-8000-000000000777",
            inputLotIds = listOf("0190f0de-0000-7000-8000-000000000301"),
            outputSkuId = "0190f0de-0000-7000-8000-000000000105",
            outputBatchId = "0190f0de-0000-7000-8000-000000000205",
            outputUom = "PK",
            actualOutputQty = Qty.of(48),
            consumedLots = listOf(
                ConsumedLotInput(
                    lotId = "0190f0de-0000-7000-8000-000000000301",
                    skuId = "0190f0de-0000-7000-8000-000000000104",
                    consumedQty = Qty.parse("50.000"),
                    unitCost = Money.parse("200.00"),
                ),
            ),
        )

        val bundle = ShopBundleBuilder.buildRepackBundle(
            device = device,
            operatorUserId = operatorUserId,
            businessDate = businessDate,
            clockReading = clockReading,
            zone = zone,
            input = input,
            docNumber = 3L,
            deviceSeq = 103L,
            ids = ids,
        )

        assertEquals("repack.executed.v1", bundle.eventType)
        assertEquals("RPK", bundle.document.docTypeCode)
        assertEquals("EXECUTED", bundle.document.status)
        assertEquals(1, bundle.lines.size)
        assertEquals("PK", bundle.lines[0].uomCode)
        assertEquals(Qty.of(48), bundle.lines[0].qty)

        // 1 consume movement + 1 produce movement
        assertEquals(2, bundle.stockMovements.size)
        assertEquals("REPACK_CONSUME", bundle.stockMovements[0].movementType)
        assertEquals("-50.000", bundle.stockMovements[0].qtyDelta)
        assertEquals("REPACK_PRODUCE", bundle.stockMovements[1].movementType)
        assertEquals("48.000", bundle.stockMovements[1].qtyDelta)
    }

    // =========================================================================
    // 5. Stock Transfer Bundle Tests
    // =========================================================================

    @Test
    fun transferBundleBuildsTransferOutMovements() {
        val destLocation = "0190f0de-0000-7000-8000-000000000200"
        val input = StockTransferOperationInput(
            destinationLocationId = destLocation,
            transferRequestId = "0190f0de-0000-7000-8000-000000000666",
            vehicleNo = "WP-CB-1234",
            lines = listOf(
                TransferLineInput(
                    skuId = "0190f0de-0000-7000-8000-000000000101",
                    batchId = "0190f0de-0000-7000-8000-000000000201",
                    uomCode = "EA",
                    qty = Qty.of(20),
                    unitCost = Money.parse("100.00"),
                ),
            ),
        )

        val bundle = ShopBundleBuilder.buildTransferBundle(
            device = device,
            operatorUserId = operatorUserId,
            businessDate = businessDate,
            clockReading = clockReading,
            zone = zone,
            input = input,
            docNumber = 9L,
            deviceSeq = 104L,
            ids = ids,
        )

        assertEquals("transfer.issued.v1", bundle.eventType)
        assertEquals("XFR", bundle.document.docTypeCode)
        assertEquals("ISSUED", bundle.document.status)
        assertEquals(1, bundle.lines.size)
        assertEquals(Money.parse("2000.00"), bundle.document.netAmount)

        assertEquals(1, bundle.stockMovements.size)
        assertEquals("TRANSFER_OUT", bundle.stockMovements[0].movementType)
        assertEquals("-20.000", bundle.stockMovements[0].qtyDelta)
    }

    @Test
    fun transferRejectsSameDestinationAsSource() {
        val input = StockTransferOperationInput(
            destinationLocationId = device.locationId,
            lines = listOf(
                TransferLineInput(
                    skuId = "0190f0de-0000-7000-8000-000000000101",
                    uomCode = "EA",
                    qty = Qty.of(1),
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            ShopBundleBuilder.buildTransferBundle(device, operatorUserId, businessDate, clockReading, zone, input, 1L, 1L)
        }
    }

    // =========================================================================
    // 6. TillService End-to-End Coordination & Monotonic Sequence Tests
    // =========================================================================

    @Test
    fun tillServiceCoordinatesMixedOperationsWithDenseSequenceNumbers() = runBlocking {
        val fixture = TillFixture().signedInWithSession()
        val service = fixture.service
        val store = fixture.store

        // Record a GRN
        val grn = service.recordGrn(
            GrnOperationInput(
                lines = listOf(
                    GrnLineInput(
                        skuId = "0190f0de-0000-7000-8000-000000000101",
                        uomCode = "EA",
                        receivedQty = Qty.of(10),
                        unitCost = Money.parse("100.00"),
                    ),
                ),
            ),
        )
        assertEquals(2L, grn.document.deviceSeq) // 1 was session opened

        // Record a Stock Count
        val count = service.recordStockCount(
            StockCountOperationInput(
                lines = listOf(
                    StockCountLineInput(
                        skuId = "0190f0de-0000-7000-8000-000000000101",
                        uomCode = "EA",
                        countedQty = Qty.of(10),
                    ),
                ),
            ),
        )
        assertEquals(3L, count.document.deviceSeq)

        // Record a Write-off
        val wof = service.recordWriteOff(
            WriteOffOperationInput(
                category = "DAMAGE",
                reason = "Dropped during shelf stocking",
                lines = listOf(
                    WriteOffLineInput(
                        skuId = "0190f0de-0000-7000-8000-000000000101",
                        uomCode = "EA",
                        qty = Qty.of(1),
                        unitCost = Money.parse("100.00"),
                    ),
                ),
            ),
        )
        assertEquals(4L, wof.document.deviceSeq)

        // Record a Repack
        val repack = service.recordRepack(
            RepackOperationInput(
                recipeId = "0190f0de-0000-7000-8000-000000000777",
                inputLotIds = listOf("0190f0de-0000-7000-8000-000000000301"),
                outputSkuId = "0190f0de-0000-7000-8000-000000000105",
                outputUom = "PK",
                actualOutputQty = Qty.of(9),
            ),
        )
        assertEquals(5L, repack.document.deviceSeq)

        // Record a Transfer
        val transfer = service.recordStockTransfer(
            StockTransferOperationInput(
                destinationLocationId = "0190f0de-0000-7000-8000-000000000200",
                lines = listOf(
                    TransferLineInput(
                        skuId = "0190f0de-0000-7000-8000-000000000101",
                        uomCode = "EA",
                        qty = Qty.of(2),
                        unitCost = Money.parse("100.00"),
                    ),
                ),
            ),
        )
        assertEquals(6L, transfer.document.deviceSeq)

        // Verify outbox sequence numbers are completely dense without gaps
        val pending = store.pendingOutbox(10)
        assertEquals(6, pending.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), pending.map { it.deviceSeq })
    }

    @Test
    fun duplicateOperationIdThrowsRefusalAndDoesNotConsumeSequence() = runBlocking {
        val fixture = TillFixture().signedInWithSession()
        val service = fixture.service
        val store = fixture.store

        val opId = "0190f0de-0000-7000-8000-000000009999"
        val input = GrnOperationInput(
            lines = listOf(
                GrnLineInput(
                    skuId = "0190f0de-0000-7000-8000-000000000101",
                    uomCode = "EA",
                    receivedQty = Qty.of(5),
                ),
            ),
        )

        service.recordGrn(input, operationId = opId)
        assertEquals(2L, store.maxOutboxSeq())

        // Calling with same operationId throws refusal
        assertFailsWith<TillRefusal> {
            service.recordGrn(input, operationId = opId)
        }

        // Sequence number must not have been consumed
        assertEquals(2L, store.maxOutboxSeq())
    }
}
