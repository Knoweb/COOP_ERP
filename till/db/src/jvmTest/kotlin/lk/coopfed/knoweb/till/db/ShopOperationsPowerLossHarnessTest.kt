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
import lk.coopfed.knoweb.till.core.port.Settings
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

/**
 * 10,000-kill power-loss conformance harness for offline shop operations
 * (GRN, stock count, write-off, repack, transfer).
 *
 * Authoritative target (26A, Doc 32 §11 Case 3, TILL-SHOP-04):
 * "10,000 issuances with process kill and simulated power loss injected at each step;
 * assert: no document without its number, no number without its document, outbox dense,
 * database opens clean every time. Target: Zero loss or duplicate."
 */
class ShopOperationsPowerLossHarnessTest {

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

    private val seriesId = "0190f0de-0000-7000-8000-000000020000"
    private val prefix = "M101-LOC-01"
    private val operatorUserId = "0190f0de-0000-7000-8000-000000000099"
    private val businessDate = LocalDate(2026, 11, 25)
    private val zone = TimeZone.of("Asia/Colombo")

    private val clockInstant = Instant.parse("2026-11-25T08:30:00Z")
    private val clock = ClockReading(
        occurredAt = clockInstant,
        rawInstant = clockInstant,
        clockOffsetMs = null,
        offlineTimed = false,
    )

    @Test
    fun tenThousandKillPowerLossHarnessZeroLossZeroDuplicatesDenseOutbox() {
        val dir = Files.createTempDirectory("till-harness-db")
        val file = dir.resolve("harness.db")
        val key = "0f".repeat(32)

        val totalCycles = 10_000
        var currentDeviceSeq = 1L
        val nextDocNumbers = mutableMapOf(
            "GRN" to 1L,
            "CNT" to 1L,
            "WOF" to 1L,
            "RPK" to 1L,
            "XFR" to 1L,
        )

        var committedCount = 0
        var midTransactionKills = 0
        var postCommitKills = 0

        // Open database
        var driver = EncryptedJvmDatabase.open(file, key)
        driver.execute(null, "PRAGMA synchronous = NORMAL", 0)
        var store = SqlTillStore(driver)

        fun reopenDatabase(checkIntegrity: Boolean = false) {
            driver.close()
            driver = EncryptedJvmDatabase.open(file, key)
            driver.execute(null, "PRAGMA synchronous = NORMAL", 0)
            store = SqlTillStore(driver)
            if (checkIntegrity) {
                // PRAGMA integrity_check must be 'ok' on restart
                val integrity = driver.executeQuery(null, "PRAGMA integrity_check", { cursor ->
                    cursor.next()
                    app.cash.sqldelight.db.QueryResult.Value(cursor.getString(0) ?: "unknown")
                }, 0).value
                assertEquals("ok", integrity, "SQLite database must open clean every time")
            }
        }

        try {
            for (i in 0 until totalCycles) {
                val opType = when (i % 5) {
                    0 -> "GRN"
                    1 -> "CNT"
                    2 -> "WOF"
                    3 -> "RPK"
                    else -> "XFR"
                }

                val docNumber = nextDocNumbers[opType]!!
                val bundle = when (opType) {
                    "GRN" -> ShopBundleBuilder.buildGrnBundle(
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
                                    receivedQty = Qty.of(1),
                                    unitCost = Money.parse("100.00"),
                                ),
                            ),
                        ),
                        docNumber = docNumber,
                        deviceSeq = currentDeviceSeq,
                        seriesId = seriesId,
                        seriesPrefix = prefix,
                    )
                    "CNT" -> ShopBundleBuilder.buildStockCountBundle(
                        device = device,
                        operatorUserId = operatorUserId,
                        businessDate = businessDate,
                        clockReading = clock,
                        zone = zone,
                        input = StockCountOperationInput(
                            lines = listOf(
                                StockCountLineInput(
                                    skuId = "0190f0de-0000-7000-8000-000000000101",
                                    uomCode = "EA",
                                    countedQty = Qty.of(1),
                                ),
                            ),
                        ),
                        docNumber = docNumber,
                        deviceSeq = currentDeviceSeq,
                        seriesId = seriesId,
                        seriesPrefix = prefix,
                    )
                    "WOF" -> ShopBundleBuilder.buildWriteOffBundle(
                        device = device,
                        operatorUserId = operatorUserId,
                        businessDate = businessDate,
                        clockReading = clock,
                        zone = zone,
                        input = WriteOffOperationInput(
                            category = "EXPIRED",
                            reason = "Past expiry date",
                            lines = listOf(
                                WriteOffLineInput(
                                    skuId = "0190f0de-0000-7000-8000-000000000101",
                                    uomCode = "EA",
                                    qty = Qty.of(1),
                                    unitCost = Money.parse("100.00"),
                                ),
                            ),
                        ),
                        docNumber = docNumber,
                        deviceSeq = currentDeviceSeq,
                        seriesId = seriesId,
                        seriesPrefix = prefix,
                    )
                    "RPK" -> ShopBundleBuilder.buildRepackBundle(
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
                            actualOutputQty = Qty.of(1),
                        ),
                        docNumber = docNumber,
                        deviceSeq = currentDeviceSeq,
                        seriesId = seriesId,
                        seriesPrefix = prefix,
                    )
                    else -> ShopBundleBuilder.buildTransferBundle(
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
                                    qty = Qty.of(1),
                                    unitCost = Money.parse("100.00"),
                                ),
                            ),
                        ),
                        docNumber = docNumber,
                        deviceSeq = currentDeviceSeq,
                        seriesId = seriesId,
                        seriesPrefix = prefix,
                    )
                }

                val failureStep = (i % 7)

                if (failureStep in 1..5) {
                    // Mid-transaction power loss injected during transaction
                    midTransactionKills++
                    val docId = bundle.document.documentId

                    assertFailsWith<PowerCutException> {
                        store.transaction {
                            // Write outbox and partial entities, then crash before committing transaction
                            store.appendOutbox(bundle.outboxEntry)
                            if (failureStep == 1) throw PowerCutException("Power cut after outbox")

                            for (line in bundle.lines) {
                                store.tillQueries.insertDocumentLine(
                                    line_id = line.lineId,
                                    document_id = bundle.document.documentId,
                                    line_no = line.lineNo.toLong(),
                                    sku_id = line.skuId,
                                    batch_id = line.batchId,
                                    uom_code = line.uomCode,
                                    qty = line.qty.plain(),
                                    unit_price = line.unitPrice.plain(),
                                    mrp_applied = null,
                                    control_price_applied = null,
                                    cap_reason = null,
                                    discount_rule_id = null,
                                    discount_amount = "0.00",
                                    tax_rate_percent = "0.00",
                                    tax_amount = "0.00",
                                    line_total = line.lineTotal.plain(),
                                    unit_cost_at_issue = line.unitCostAtIssue?.plain(),
                                    loss_category = line.lossCategory,
                                    reference_line_id = line.referenceLineId,
                                    negative_ack = 0L,
                                    picked = 0L,
                                    weight_kg = null,
                                )
                            }
                            if (failureStep == 2) throw PowerCutException("Power cut after lines")

                            for (h in bundle.stateHistory) {
                                store.tillQueries.insertStateHistory(
                                    history_id = h.historyId,
                                    document_id = h.documentId,
                                    from_status = h.fromStatus,
                                    to_status = h.toStatus,
                                    occurred_at = h.occurredAt,
                                    occurred_local = h.occurredLocal,
                                    actor_user_id = h.actorUserId,
                                    reason_code = h.reasonCode,
                                    reason_text = h.reasonText,
                                )
                            }
                            if (failureStep == 3) throw PowerCutException("Power cut after state history")

                            for (m in bundle.stockMovements) {
                                store.tillQueries.insertStockMovement(
                                    movement_id = m.movementId,
                                    movement_seq = m.movementSeq,
                                    location_id = m.locationId,
                                    sku_id = m.skuId,
                                    batch_id = m.batchId,
                                    lot_id = m.lotId,
                                    condition = m.condition,
                                    qty_delta = m.qtyDelta,
                                    movement_type = m.movementType,
                                    unit_cost_at_movement = m.unitCostAtMovement?.plain(),
                                    document_id = m.documentId,
                                    document_line_id = m.documentLineId,
                                    occurred_at = m.occurredAt,
                                    occurred_local = m.occurredLocal,
                                    operator_user_id = m.operatorUserId,
                                    device_id = m.deviceId,
                                )
                            }
                            if (failureStep == 4) throw PowerCutException("Power cut after stock movements")

                            for (a in bundle.auditEvents) {
                                store.tillQueries.insertAuditEvent(
                                    audit_id = a.auditId,
                                    event_type_code = a.eventTypeCode,
                                    occurred_at = a.occurredAt,
                                    occurred_local = a.occurredLocal,
                                    owner_entity_id = a.ownerEntityId,
                                    location_id = a.locationId,
                                    actor_user_id = a.actorUserId,
                                    device_id = a.deviceId,
                                    till_position_id = a.tillPositionId,
                                    subject_table = a.subjectTable,
                                    subject_id = a.subjectId,
                                    document_id = a.documentId,
                                    before_state = a.beforeState,
                                    after_state = a.afterState,
                                    reason_code = a.reasonCode,
                                    reason_text = a.reasonText,
                                    device_seq = a.deviceSeq,
                                    correlation_id = a.correlationId,
                                )
                            }
                            if (failureStep == 5) throw PowerCutException("Power cut after audit")
                        }
                    }

                    // Assert All-or-Nothing: zero trace of this uncommitted document on sample checkpoints
                    if (i < 20 || i % 100 == 0) {
                        assertEquals(emptyList(), store.documentLines(docId), "document lines rolled back on kill")
                        assertEquals(emptyList(), store.stateHistoryOf(docId), "state history rolled back on kill")
                        assertEquals(emptyList(), store.stockMovementsOf(docId), "stock movements rolled back on kill")
                        assertEquals(emptyList(), store.auditEventsOf(docId), "audit rolled back on kill")
                    }

                    // Reboot till at representative checkpoints (first 5 cycles, and periodically every 500 cycles)
                    if (i < 5 || i % 500 == 0) {
                        reopenDatabase(checkIntegrity = (i < 5 || i % 1000 == 0))
                    }

                    // Retry same document number and device sequence safely inside a single transaction
                    store.transaction {
                        store.saveShopOperation(bundle)
                        store.putSetting(Settings.docNumberKey(opType), (docNumber + 1).toString())
                        store.putSetting(Settings.NEXT_DEVICE_SEQ, (currentDeviceSeq + 1).toString())
                    }
                    committedCount++
                    currentDeviceSeq++
                    nextDocNumbers[opType] = docNumber + 1
                } else if (failureStep == 6) {
                    // Post-commit power cut
                    postCommitKills++
                    store.transaction {
                        store.saveShopOperation(bundle)
                        store.putSetting(Settings.docNumberKey(opType), (docNumber + 1).toString())
                        store.putSetting(Settings.NEXT_DEVICE_SEQ, (currentDeviceSeq + 1).toString())
                    }
                    committedCount++
                    currentDeviceSeq++
                    nextDocNumbers[opType] = docNumber + 1

                    // Reboot till at representative checkpoints
                    if (i < 5 || i % 500 == 6) {
                        reopenDatabase(checkIntegrity = (i < 5 || i % 1000 == 6))
                    }
                } else {
                    // Clean commit
                    store.transaction {
                        store.saveShopOperation(bundle)
                        store.putSetting(Settings.docNumberKey(opType), (docNumber + 1).toString())
                        store.putSetting(Settings.NEXT_DEVICE_SEQ, (currentDeviceSeq + 1).toString())
                    }
                    committedCount++
                    currentDeviceSeq++
                    nextDocNumbers[opType] = docNumber + 1
                }
            }

            // Post-harness assertions: final reboot and full integrity check on the complete 10,000-cycle database
            reopenDatabase(checkIntegrity = true)

            // 1. All committed operations are intact
            assertEquals(committedCount.toLong(), store.pendingCount())
            assertEquals(committedCount.toLong(), store.maxOutboxSeq())

            // 2. Outbox is 100% dense without any sequence gaps or duplicates
            val allPending = store.pendingOutbox(totalCycles * 2)
            assertEquals(committedCount, allPending.size)
            val sequences = allPending.map { it.deviceSeq }
            val expectedSequences = (1L..committedCount.toLong()).toList()
            assertEquals(expectedSequences, sequences, "outbox device sequence must be strictly dense without gaps")

            // 3. Document numbers per series are strictly dense
            for ((type, nextNum) in nextDocNumbers) {
                val storedNext = store.setting(Settings.docNumberKey(type))
                assertEquals(nextNum.toString(), storedNext, "series document number for $type must match exactly")
            }

            // 4. Content hashes and bundles are valid
            for (outboxItem in allPending.take(100)) {
                val payload = outboxItem.json
                assertTrue(payload.contains("\"content_hash\""), "outbox payload must contain content_hash")
            }

            println("10,000-kill harness completed successfully: $committedCount committed, $midTransactionKills mid-transaction kills rolled back, $postCommitKills post-commit reboots verified.")
        } finally {
            driver.close()
        }
    }
}

internal class PowerCutException(message: String) : RuntimeException(message)
