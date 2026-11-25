package lk.coopfed.knoweb.till.db

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import lk.coopfed.knoweb.till.core.model.ClockReading
import lk.coopfed.knoweb.till.core.model.DeviceIdentity
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.money.Qty
import lk.coopfed.knoweb.till.core.shop.ConsumedLotInput
import lk.coopfed.knoweb.till.core.shop.GrnLineInput
import lk.coopfed.knoweb.till.core.shop.GrnOperationInput
import lk.coopfed.knoweb.till.core.shop.RepackOperationInput
import lk.coopfed.knoweb.till.core.shop.ShopBundleBuilder
import lk.coopfed.knoweb.till.core.shop.StockCountLineInput
import lk.coopfed.knoweb.till.core.shop.StockCountOperationInput
import lk.coopfed.knoweb.till.core.shop.StockTransferOperationInput
import lk.coopfed.knoweb.till.core.shop.TransferLineInput
import lk.coopfed.knoweb.till.core.shop.WriteOffLineInput
import lk.coopfed.knoweb.till.core.shop.WriteOffOperationInput

class ShopOperationPersistenceTest {

    private val dir = Files.createTempDirectory("till-shop-db")
    private val file = dir.resolve("till-shop.db")
    private val key = "0f".repeat(32)

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

    private val clockInstant = Instant.parse("2026-11-25T08:30:00Z")
    private val clock = ClockReading(
        occurredAt = clockInstant,
        rawInstant = clockInstant,
        clockOffsetMs = null,
        offlineTimed = false,
    )

    private val businessDate = LocalDate(2026, 11, 25)
    private val zone = TimeZone.of("Asia/Colombo")
    private val operatorUserId = "0190f0de-0000-7000-8000-000000000099"

    @Test
    fun allFiveShopOperationsPersistAtomicallyAndRecoverAcrossDatabaseRestart() {
        // 1. Build bundles for all 5 operations
        val grn = ShopBundleBuilder.buildGrnBundle(
            device = device,
            operatorUserId = operatorUserId,
            businessDate = businessDate,
            clockReading = clock,
            zone = zone,
            input = GrnOperationInput(
                supplierId = "0190f0de-0000-7000-8000-000000000555",
                lines = listOf(
                    GrnLineInput(
                        skuId = "0190f0de-0000-7000-8000-000000000101",
                        uomCode = "EA",
                        receivedQty = Qty.of(10),
                        unitCost = Money.parse("100.00"),
                        batchNo = "B100",
                    ),
                ),
            ),
            docNumber = 1L,
            deviceSeq = 1L,
        )

        val count = ShopBundleBuilder.buildStockCountBundle(
            device = device,
            operatorUserId = operatorUserId,
            businessDate = businessDate,
            clockReading = clock,
            zone = zone,
            input = StockCountOperationInput(
                taskId = "0190f0de-0000-7000-8000-000000000888",
                lines = listOf(
                    StockCountLineInput(
                        skuId = "0190f0de-0000-7000-8000-000000000101",
                        uomCode = "EA",
                        countedQty = Qty.of(10),
                    ),
                ),
            ),
            docNumber = 2L,
            deviceSeq = 2L,
        )

        val wof = ShopBundleBuilder.buildWriteOffBundle(
            device = device,
            operatorUserId = operatorUserId,
            businessDate = businessDate,
            clockReading = clock,
            zone = zone,
            input = WriteOffOperationInput(
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
            docNumber = 3L,
            deviceSeq = 3L,
        )

        val repack = ShopBundleBuilder.buildRepackBundle(
            device = device,
            operatorUserId = operatorUserId,
            businessDate = businessDate,
            clockReading = clock,
            zone = zone,
            input = RepackOperationInput(
                recipeId = "0190f0de-0000-7000-8000-000000000777",
                inputLotIds = listOf("0190f0de-0000-7000-8000-000000000301"),
                outputSkuId = "0190f0de-0000-7000-8000-000000000105",
                outputUom = "PK",
                actualOutputQty = Qty.of(9),
                consumedLots = listOf(
                    ConsumedLotInput(
                        lotId = "0190f0de-0000-7000-8000-000000000301",
                        skuId = "0190f0de-0000-7000-8000-000000000101",
                        consumedQty = Qty.of(10),
                        unitCost = Money.parse("100.00"),
                    ),
                ),
            ),
            docNumber = 4L,
            deviceSeq = 4L,
        )

        val transfer = ShopBundleBuilder.buildTransferBundle(
            device = device,
            operatorUserId = operatorUserId,
            businessDate = businessDate,
            clockReading = clock,
            zone = zone,
            input = StockTransferOperationInput(
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
            docNumber = 5L,
            deviceSeq = 5L,
        )

        // 2. Save in encrypted SQLite
        EncryptedJvmDatabase.open(file, key).use { driver ->
            val store = SqlTillStore(driver)
            store.saveShopOperation(grn)
            store.saveShopOperation(count)
            store.saveShopOperation(wof)
            store.saveShopOperation(repack)
            store.saveShopOperation(transfer)
        }

        // 3. Re-open and verify complete persistence across restart
        EncryptedJvmDatabase.open(file, key).use { driver ->
            val store = SqlTillStore(driver)

            // Check outbox holds all 5 operations densely
            assertEquals(5, store.pendingCount())
            val pending = store.pendingOutbox(10)
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), pending.map { it.deviceSeq })
            assertEquals(
                listOf("grn.confirmed.v1", "count.recorded.v1", "writeoff.requested.v1", "repack.executed.v1", "transfer.issued.v1"),
                pending.map { it.eventType },
            )

            // Check document lines
            val grnLines = store.documentLines(grn.document.documentId)
            assertEquals(1, grnLines.size)
            assertEquals("0190f0de-0000-7000-8000-000000000101", grnLines[0].skuId)
            assertEquals(Qty.of(10), grnLines[0].qty)
            assertEquals(Money.parse("100.00"), grnLines[0].unitPrice)
            assertEquals(Money.parse("1000.00"), grnLines[0].lineTotal)

            val countLines = store.documentLines(count.document.documentId)
            assertEquals(1, countLines.size)
            assertEquals(Qty.of(10), countLines[0].qty)

            val wofLines = store.documentLines(wof.document.documentId)
            assertEquals(1, wofLines.size)
            assertEquals(Qty.of(1), wofLines[0].qty)

            val transferLines = store.documentLines(transfer.document.documentId)
            assertEquals(1, transferLines.size)
            assertEquals(Qty.of(2), transferLines[0].qty)

            // Check state history
            val grnHistory = store.stateHistoryOf(grn.document.documentId)
            assertEquals(1, grnHistory.size)
            assertEquals("CONFIRMED", grnHistory[0].toStatus)

            val countHistory = store.stateHistoryOf(count.document.documentId)
            assertEquals("RECORDED", countHistory[0].toStatus)

            val wofHistory = store.stateHistoryOf(wof.document.documentId)
            assertEquals("REQUESTED", wofHistory[0].toStatus)

            val repackHistory = store.stateHistoryOf(repack.document.documentId)
            assertEquals("EXECUTED", repackHistory[0].toStatus)

            val transferHistory = store.stateHistoryOf(transfer.document.documentId)
            assertEquals("ISSUED", transferHistory[0].toStatus)

            // Check stock movements
            val grnMovements = store.stockMovementsOf(grn.document.documentId)
            assertEquals(1, grnMovements.size)
            assertEquals("GOOD", grnMovements[0].condition)
            assertEquals("10.000", grnMovements[0].qtyDelta)

            val wofMovements = store.stockMovementsOf(wof.document.documentId)
            assertEquals(1, wofMovements.size)
            assertEquals("-1.000", wofMovements[0].qtyDelta)

            val repackMovements = store.stockMovementsOf(repack.document.documentId)
            assertEquals(2, repackMovements.size) // 1 in, 1 out

            val transferMovements = store.stockMovementsOf(transfer.document.documentId)
            assertEquals(1, transferMovements.size)
            assertEquals("-2.000", transferMovements[0].qtyDelta)

            // Check audit
            val grnAudit = store.auditEventsOf(grn.document.documentId)
            assertEquals(1, grnAudit.size)
            assertEquals("GRN_CONFIRMED", grnAudit[0].eventTypeCode)

            val wofAudit = store.auditEventsOf(wof.document.documentId)
            assertEquals(1, wofAudit.size)
            assertEquals("WRITEOFF_REQUESTED", wofAudit[0].eventTypeCode)
        }
    }

    @Test
    fun transactionFailureRollsBackAllTablesAtomically() {
        val grn = ShopBundleBuilder.buildGrnBundle(
            device = device,
            operatorUserId = operatorUserId,
            businessDate = businessDate,
            clockReading = clock,
            zone = zone,
            input = GrnOperationInput(
                lines = listOf(
                    GrnLineInput(
                        skuId = "0190f0de-0000-7000-8000-000000000101",
                        uomCode = "EA",
                        receivedQty = Qty.of(5),
                    ),
                ),
            ),
            docNumber = 99L,
            deviceSeq = 99L,
        )

        EncryptedJvmDatabase.open(file, key).use { driver ->
            val store = SqlTillStore(driver)
            assertFailsWith<IllegalStateException> {
                store.transaction {
                    store.saveShopOperation(grn)
                    throw IllegalStateException("Simulated power loss before transaction commit")
                }
            }

            // Verify completely rolled back
            assertEquals(emptyList(), store.documentLines(grn.document.documentId))
            assertEquals(emptyList(), store.stateHistoryOf(grn.document.documentId))
            assertEquals(emptyList(), store.stockMovementsOf(grn.document.documentId))
            assertEquals(emptyList(), store.auditEventsOf(grn.document.documentId))
            assertTrue(store.pendingOutbox(100).none { it.deviceSeq == 99L })
        }
    }
}
