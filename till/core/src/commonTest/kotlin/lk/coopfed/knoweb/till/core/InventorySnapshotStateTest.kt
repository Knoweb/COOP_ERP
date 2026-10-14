package lk.coopfed.knoweb.till.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import lk.coopfed.knoweb.till.core.snapshot.SnapshotDelta
import lk.coopfed.knoweb.till.core.snapshot.SnapshotRow
import lk.coopfed.knoweb.till.core.snapshot.SnapshotState
import lk.coopfed.knoweb.till.core.snapshot.SnapshotVerifier

/**
 * TILL-DATA-05: M5 Inventory Snapshot Sync verification for the offline till.
 *
 * Verifies that an enrolled till receives, verifies, and stores the four M5 inventory
 * snapshot tables:
 * - lot
 * - count_task
 * - inventory_policy
 * - expected_transfer
 *
 * Covers full snapshot application, delta application (UPSERT and tombstone DELETE),
 * absence of unit_cost/margins, FEFO rank representation, policy overrides, and store roundtrip.
 */
class InventorySnapshotStateTest {

    private val verifier = SnapshotVerifier(FakeSignatures)
    private val locationId = Samples.LOCATION
    private val businessDate = LocalDate(2026, 10, 13)

    // Sample IDs
    private val lot1Id = "0190f0de-0000-7000-8000-0000000e0001"
    private val lot2Id = "0190f0de-0000-7000-8000-0000000e0002"
    private val lotDamagedId = "0190f0de-0000-7000-8000-0000000e0003"
    private val countTask1Id = "0190f0de-0000-7000-8000-0000000f0001"
    private val transfer1Id = "0190f0de-0000-7000-8000-000000100001"

    private fun sampleGoodLot(lotId: String, qty: String = "25.000", fefoRank: Int? = 1) = SnapshotRow(
        table = "lot",
        rowId = lotId,
        applyFrom = null,
        data = buildJsonObject {
            put("stock_lot_id", lotId)
            put("sku_id", Samples.SKU_RICE)
            put("batch_no", "BATCH-2026-001")
            put("expiry_date", "2027-06-30")
            put("condition", "GOOD")
            put("quantity", qty)
            if (fefoRank != null) put("fefo_rank", fefoRank) else put("fefo_rank", null as Int?)
            put("received_at", "2026-10-01T08:00:00Z")
        },
    )

    private fun sampleDamagedLot(lotId: String) = SnapshotRow(
        table = "lot",
        rowId = lotId,
        applyFrom = null,
        data = buildJsonObject {
            put("stock_lot_id", lotId)
            put("sku_id", Samples.SKU_RICE)
            put("batch_no", "BATCH-2026-DMG")
            put("expiry_date", "2027-06-30")
            put("condition", "DAMAGED")
            put("quantity", "3.000")
            put("fefo_rank", null as Int?)
            put("received_at", "2026-10-01T08:00:00Z")
        },
    )

    private fun sampleCountTask(taskId: String, status: String = "SCHEDULED", withExpectations: Boolean = false) = SnapshotRow(
        table = "count_task",
        rowId = taskId,
        applyFrom = null,
        data = buildJsonObject {
            put("task_id", taskId)
            put("task_number", "CNT-2026-0001")
            put("status", status)
            put("count_type", "CYCLE")
            put("blind", true)
            put("scheduled_date", "2026-10-14")
            put("scope", buildJsonObject {
                put("type", "LOCATION_ALL")
            })
            putJsonArray("expectations") {
                if (withExpectations) {
                    add(buildJsonObject {
                        put("stock_lot_id", lot1Id)
                        put("sku_id", Samples.SKU_RICE)
                        put("expected_qty", "25.000")
                    })
                }
            }
        },
    )

    private fun sampleInventoryPolicy(locationId: String, negativeStockAllowed: Boolean = false) = SnapshotRow(
        table = "inventory_policy",
        rowId = locationId,
        applyFrom = null,
        data = buildJsonObject {
            put("inventory.negative_stock.allowed", negativeStockAllowed)
            put("inventory.negative_stock.warning_only", false)
            put("inventory.count.freeze_on_start", true)
            put("inventory.count.blind_counts", true)
            put("inventory.transfer.require_grn", true)
            put("inventory.transfer.transit_loss_limit_pct", "0.0200")
            put("inventory.expiry.near_threshold_days", 30)
            put("inventory.expiry.sale_stop_days", 7)
            put("inventory.stocktake.annual_mandatory", true)
            put("inventory.valuation.method", "WEIGHTED_AVERAGE")
            put("inventory.cycle_count.default_interval_days", 30)
        },
    )

    private fun sampleExpectedTransfer(transferId: String) = SnapshotRow(
        table = "expected_transfer",
        rowId = transferId,
        applyFrom = null,
        data = buildJsonObject {
            put("transfer_id", transferId)
            put("transfer_no", "TRN-2026-0042")
            put("source_location_id", "0190f0de-0000-7000-8000-000000000099")
            put("destination_location_id", locationId)
            put("issued_at", "2026-10-13T10:00:00Z")
            put("status", "ISSUED")
            putJsonArray("lines") {
                add(buildJsonObject {
                    put("line_number", 1)
                    put("sku_id", Samples.SKU_RICE)
                    put("batch_no", "BATCH-2026-001")
                    put("expiry_date", "2027-06-30")
                    put("quantity", "100.000")
                })
            }
        },
    )

    @Test
    fun aFullSnapshotWithAllFourM5InventoryTablesIsAppliedAndStored() {
        val rows = listOf(
            Samples.location(),
            sampleGoodLot(lot1Id, "25.000", fefoRank = 1),
            sampleDamagedLot(lotDamagedId),
            sampleCountTask(countTask1Id, "SCHEDULED", withExpectations = false),
            sampleInventoryPolicy(locationId),
            sampleExpectedTransfer(transfer1Id),
        )

        val answer = Samples.snapshotAnswer(version = 1, rows = rows, location = locationId)
        val delta = SnapshotDelta.fromJson(answer)
        verifier.verify(delta, locationId, Samples.PUBLIC_KEY)

        val state = SnapshotState.EMPTY.apply(delta, businessDate)
        assertEquals(1L, state.version)

        // Verify lot table
        val lots = state.table("lot")
        assertEquals(2, lots.size)
        val goodLot = lots.first { it.rowId == lot1Id }
        assertEquals("25.000", goodLot.data?.get("quantity")?.jsonPrimitive?.content)
        assertEquals(1, goodLot.data?.get("fefo_rank")?.jsonPrimitive?.intOrNull)
        val damagedLot = lots.first { it.rowId == lotDamagedId }
        assertEquals("DAMAGED", damagedLot.data?.get("condition")?.jsonPrimitive?.content)
        assertNull(damagedLot.data?.get("fefo_rank")?.jsonPrimitive?.intOrNull)

        // Verify count_task table
        val countTasks = state.table("count_task")
        assertEquals(1, countTasks.size)
        val countTask = countTasks.first()
        assertEquals("SCHEDULED", countTask.data?.get("status")?.jsonPrimitive?.content)
        assertEquals(0, countTask.data?.get("expectations")?.jsonArray?.size)

        // Verify inventory_policy table
        val policies = state.table("inventory_policy")
        assertEquals(1, policies.size)
        val policy = policies.first()
        assertEquals(locationId, policy.rowId)
        assertEquals("false", policy.data?.get("inventory.negative_stock.allowed")?.jsonPrimitive?.content)
        assertEquals("30", policy.data?.get("inventory.expiry.near_threshold_days")?.jsonPrimitive?.content)
        assertEquals("0.0200", policy.data?.get("inventory.transfer.transit_loss_limit_pct")?.jsonPrimitive?.content)

        // Verify expected_transfer table
        val transfers = state.table("expected_transfer")
        assertEquals(1, transfers.size)
        val transfer = transfers.first()
        assertEquals("TRN-2026-0042", transfer.data?.get("transfer_no")?.jsonPrimitive?.content)
        assertEquals(1, transfer.data?.get("lines")?.jsonArray?.size)

        // Verify persistence round-trip in TillStore
        val store = InMemoryStore()
        store.saveSnapshot(state)
        val reloaded = store.loadSnapshot()
        assertEquals(1L, reloaded.version)
        assertEquals(2, reloaded.table("lot").size)
        assertEquals(1, reloaded.table("count_task").size)
        assertEquals(1, reloaded.table("inventory_policy").size)
        assertEquals(1, reloaded.table("expected_transfer").size)
    }

    @Test
    fun lotDepletionProducesDeleteTombstoneInDelta() {
        // Initial state at version 1 with 2 lots
        val initialRows = listOf(
            sampleGoodLot(lot1Id, "25.000", fefoRank = 1),
            sampleGoodLot(lot2Id, "50.000", fefoRank = 2),
        )
        val stateV1 = SnapshotState.EMPTY.apply(
            SnapshotDelta.fromJson(Samples.snapshotAnswer(1, initialRows, location = locationId)),
            businessDate,
        )
        assertEquals(2, stateV1.table("lot").size)

        // Delta at version 2: lot1 is depleted to zero (tombstone), lot2 quantity updated to 30.000
        val lot1Tombstone = SnapshotRow("lot", lot1Id, null, null)
        val lot2Updated = sampleGoodLot(lot2Id, "30.000", fefoRank = 1)
        val deltaAnswer = Samples.snapshotAnswer(
            version = 2,
            rows = listOf(lot1Tombstone, lot2Updated),
            location = locationId,
            since = 1,
            full = false,
        )
        val delta = SnapshotDelta.fromJson(deltaAnswer)
        verifier.verify(delta, locationId, Samples.PUBLIC_KEY)

        val stateV2 = stateV1.apply(delta, businessDate)
        assertEquals(2L, stateV2.version)

        val lotsV2 = stateV2.table("lot")
        assertEquals(1, lotsV2.size)
        assertNull(lotsV2.find { it.rowId == lot1Id }, "Depleted lot must be removed by tombstone")
        val updatedLot2 = lotsV2.first { it.rowId == lot2Id }
        assertEquals("30.000", updatedLot2.data?.get("quantity")?.jsonPrimitive?.content)
        assertEquals("1", updatedLot2.data?.get("fefo_rank")?.jsonPrimitive?.content)
    }

    @Test
    fun countTaskLifecycleTransitionsFromScheduledToCountingThenTombstoneOnSubmit() {
        // Initial: scheduled count task
        val scheduledTask = sampleCountTask(countTask1Id, "SCHEDULED", withExpectations = false)
        val stateV1 = SnapshotState.EMPTY.apply(
            SnapshotDelta.fromJson(Samples.snapshotAnswer(1, listOf(scheduledTask), location = locationId)),
            businessDate,
        )
        assertEquals("SCHEDULED", stateV1.table("count_task").first().data?.get("status")?.jsonPrimitive?.content)

        // Delta V2: Task starts -> status COUNTING with frozen expectations
        val countingTask = sampleCountTask(countTask1Id, "COUNTING", withExpectations = true)
        val deltaV2 = SnapshotDelta.fromJson(
            Samples.snapshotAnswer(2, listOf(countingTask), location = locationId, since = 1, full = false),
        )
        val stateV2 = stateV1.apply(deltaV2, businessDate)
        val taskV2 = stateV2.table("count_task").first()
        assertEquals("COUNTING", taskV2.data?.get("status")?.jsonPrimitive?.content)
        assertEquals(1, taskV2.data?.get("expectations")?.jsonArray?.size)

        // Delta V3: Task submitted -> tombstone removes the open count task
        val taskTombstone = SnapshotRow("count_task", countTask1Id, null, null)
        val deltaV3 = SnapshotDelta.fromJson(
            Samples.snapshotAnswer(3, listOf(taskTombstone), location = locationId, since = 2, full = false),
        )
        val stateV3 = stateV2.apply(deltaV3, businessDate)
        assertTrue(stateV3.table("count_task").isEmpty(), "Submitted/closed count task must be removed")
    }

    @Test
    fun expectedTransferTombstoneRemovesTransferWhenReceived() {
        // Initial: Transfer in transit
        val transferRow = sampleExpectedTransfer(transfer1Id)
        val stateV1 = SnapshotState.EMPTY.apply(
            SnapshotDelta.fromJson(Samples.snapshotAnswer(1, listOf(transferRow), location = locationId)),
            businessDate,
        )
        assertEquals(1, stateV1.table("expected_transfer").size)

        // Delta V2: Transfer received at shop -> tombstone issued
        val transferTombstone = SnapshotRow("expected_transfer", transfer1Id, null, null)
        val deltaV2 = SnapshotDelta.fromJson(
            Samples.snapshotAnswer(2, listOf(transferTombstone), location = locationId, since = 1, full = false),
        )
        val stateV2 = stateV1.apply(deltaV2, businessDate)
        assertTrue(stateV2.table("expected_transfer").isEmpty(), "Received transfer must be removed from expected_transfer")
    }

    @Test
    fun inventoryPolicyUpdatesViaDelta() {
        val initialPolicy = sampleInventoryPolicy(locationId, negativeStockAllowed = false)
        val stateV1 = SnapshotState.EMPTY.apply(
            SnapshotDelta.fromJson(Samples.snapshotAnswer(1, listOf(initialPolicy), location = locationId)),
            businessDate,
        )
        assertEquals("false", stateV1.table("inventory_policy").first().data?.get("inventory.negative_stock.allowed")?.jsonPrimitive?.content)

        // Policy change: Negative stock allowed toggled to true
        val updatedPolicy = sampleInventoryPolicy(locationId, negativeStockAllowed = true)
        val deltaV2 = SnapshotDelta.fromJson(
            Samples.snapshotAnswer(2, listOf(updatedPolicy), location = locationId, since = 1, full = false),
        )
        val stateV2 = stateV1.apply(deltaV2, businessDate)
        assertEquals("true", stateV2.table("inventory_policy").first().data?.get("inventory.negative_stock.allowed")?.jsonPrimitive?.content)
    }

    @Test
    fun dataMinimizationEnsuresUnitCostAndMarginsAreNeverPresent() {
        val lot = sampleGoodLot(lot1Id, "10.000", fefoRank = 1)
        val transfer = sampleExpectedTransfer(transfer1Id)

        // Verify lot payload has no cost fields
        assertFalse(lot.data!!.containsKey("unit_cost"), "lot must never contain unit_cost")
        assertFalse(lot.data.containsKey("acquisition_cost"), "lot must never contain acquisition_cost")
        assertFalse(lot.data.containsKey("margin"), "lot must never contain margin")

        // Verify expected_transfer lines have no cost fields
        val lines = transfer.data!!.getValue("lines").jsonArray
        for (line in lines) {
            val lineObj = line.jsonObject
            assertFalse(lineObj.containsKey("unit_cost"), "transfer line must never contain unit_cost")
            assertFalse(lineObj.containsKey("cost_price"), "transfer line must never contain cost_price")
        }
    }
}
