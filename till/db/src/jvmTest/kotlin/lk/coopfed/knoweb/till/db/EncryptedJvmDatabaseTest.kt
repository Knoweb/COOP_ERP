package lk.coopfed.knoweb.till.db

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import lk.coopfed.knoweb.till.core.model.Anomaly
import lk.coopfed.knoweb.till.core.model.IssuedReceipt
import lk.coopfed.knoweb.till.core.model.OutboxEntry
import lk.coopfed.knoweb.till.core.model.ReceiptLine
import lk.coopfed.knoweb.till.core.model.SessionRecord
import lk.coopfed.knoweb.till.core.model.TenderRecord
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.money.Qty
import lk.coopfed.knoweb.till.core.snapshot.SnapshotRow
import lk.coopfed.knoweb.till.core.snapshot.SnapshotState

class EncryptedJvmDatabaseTest {

    private val dir = Files.createTempDirectory("till-db")
    private val file = dir.resolve("till.db")
    private val key = "0f".repeat(32)

    @Test
    fun whatTheTillWritesComesBackAfterARestartAndIsUnreadableWithoutTheKey() {
        EncryptedJvmDatabase.open(file, key).use { driver ->
            val store = SqlTillStore(driver)
            store.transaction {
                store.putSetting("next_receipt_number", "42")
                store.saveSession(session)
                store.saveReceipt(receipt)
                store.appendOutbox(OutboxEntry(1, "e1", "receipt.issued.v1", """{"marker":"visible-only-when-decrypted"}"""))
            }
        }

        EncryptedJvmDatabase.open(file, key).use { driver ->
            val store = SqlTillStore(driver)
            assertEquals("42", store.setting("next_receipt_number"))
            assertEquals(session, store.openSession())
            assertEquals(listOf(receipt), store.receiptsOfSession(session.sessionId))
            assertEquals(1, store.pendingCount())
            store.acknowledgeUpTo(1, atMillis = 1_000)
            assertEquals(0, store.pendingCount())
            assertTrue(store.outboxHolds(1), "an acknowledged fact is kept for the retention window")
        }

        val raw = file.readBytes().decodeToString()
        assertFalse(raw.startsWith("SQLite format 3"), "the file must not be plain SQLite")
        assertFalse(raw.contains("S01-T2-1"))
        assertFailsWith<Exception> {
            DriverManager.getConnection("jdbc:sqlite:$file").use { it.createStatement().executeQuery("select count(*) from receipt").next() }
        }
    }

    @Test
    fun aReceiptNumberCannotBeWrittenTwice() {
        EncryptedJvmDatabase.open(file, key).use { driver ->
            val store = SqlTillStore(driver)
            store.saveReceipt(receipt)
            assertFailsWith<Exception> { store.saveReceipt(receipt.copy(documentId = "another")) }
        }
    }

    @Test
    fun tendersArePersistedAtomicallyWithReceiptAndCanBeQueried() {
        EncryptedJvmDatabase.open(file, key).use { driver ->
            val store = SqlTillStore(driver)
            val cardTender = TenderRecord(seq = 1, kind = "CARD", amount = Money.parse("1000.00"), reference = "AUTH-123456")
            val cashTender = TenderRecord(seq = 2, kind = "CASH", amount = Money.parse("250.00"), changeGiven = Money.parse("250.00"))
            val splitReceipt = receipt.copy(
                documentId = "d-split",
                number = 2,
                numberDisplay = "S01-T2-2",
                gross = Money.parse("1250.00"),
                tendered = Money.parse("1500.00"),
                change = Money.parse("250.00"),
                tenders = listOf(cardTender, cashTender),
            )
            store.saveReceipt(splitReceipt)

            val rows = store.tillQueries.selectTendersByDocument("d-split").executeAsList()
            assertEquals(2, rows.size)
            assertEquals("CARD", rows[0].kind)
            assertEquals("1000.00", rows[0].amount)
            assertEquals("AUTH-123456", rows[0].reference)
            assertEquals(0L, rows[0].offline)

            assertEquals("CASH", rows[1].kind)
            assertEquals("250.00", rows[1].amount)
            assertEquals("250.00", rows[1].change_given)
        }
    }

    @Test
    fun theDatabaseIsOpenedWithSynchronousFull() {
        EncryptedJvmDatabase.open(file, key).use { driver ->
            val level = driver.executeQuery(null, "PRAGMA synchronous", { cursor ->
                cursor.next()
                app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0))
            }, 0).value
            // 2 is FULL (0 OFF, 1 NORMAL, 3 EXTRA).
            assertEquals(2L, level)
        }
    }

    @Test
    fun acknowledgedFactsAreKeptUntilTheRetentionEndsAndCanBeSentAgain() {
        EncryptedJvmDatabase.open(file, key).use { driver ->
            val store = SqlTillStore(driver)
            (1L..4L).forEach { store.appendOutbox(OutboxEntry(it, "e$it", "receipt.issued.v1", "{}")) }
            store.acknowledgeUpTo(2, atMillis = 1_000)
            store.acknowledgeUpTo(3, atMillis = 5_000)
            assertEquals(listOf(4L), store.pendingOutbox(10).map { it.deviceSeq })

            assertEquals(2L, store.purgeAcknowledged(beforeMillis = 2_000))
            assertFalse(store.outboxHolds(2))
            assertTrue(store.outboxHolds(3))
            assertEquals(4L, store.maxOutboxSeq())

            store.unacknowledgeFrom(3)
            assertEquals(listOf(3L, 4L), store.pendingOutbox(10).map { it.deviceSeq })
            assertEquals(0L, store.purgeAcknowledged(beforeMillis = Long.MAX_VALUE), "a pending fact is never purged")
            assertEquals(2L, store.pendingCount())
        }
    }

    @Test
    fun anAnomalyIsRecordedOncePerFactAndCountedUntilSeen() {
        EncryptedJvmDatabase.open(file, key).use { driver ->
            val store = SqlTillStore(driver)
            val refused = Anomaly(Anomaly.QUARANTINED, 7, "e7", "HASH", "refused", 1_000)
            store.addAnomaly(refused)
            store.addAnomaly(refused.copy(detail = "the same outcome again"))
            store.addAnomaly(Anomaly(Anomaly.CLOCK, null, null, "CLOCK_JUMP", "clock", 2_000))
            store.addAnomaly(Anomaly(Anomaly.CLOCK, null, null, "CLOCK_JUMP", "clock again", 3_000))

            assertEquals(3, store.unseenAnomalyCount())
            assertEquals(1, store.quarantinedAmong(listOf(6L, 7L)))
            assertEquals(0, store.quarantinedAmong(emptyList()))
            store.markAnomaliesSeen()
            assertEquals(0, store.unseenAnomalyCount())
            assertEquals(3, store.anomalies(10).size)
            assertEquals(null, store.maxReceiptNumber())
        }
    }

    @Test
    fun inventorySnapshotTablesPersistAndReloadAcrossDatabaseRestarts() {
        val lotId = "0190f0de-0000-7000-8000-0000000e0001"
        val taskId = "0190f0de-0000-7000-8000-0000000f0001"
        val locationId = "0190f0de-0000-7000-8000-000000000132"
        val transferId = "0190f0de-0000-7000-8000-000000100001"

        val lotRow = SnapshotRow(
            "lot", lotId, null,
            buildJsonObject {
                put("stock_lot_id", lotId)
                put("sku_id", "sku-01")
                put("batch_no", "BATCH-01")
                put("expiry_date", "2027-06-30")
                put("condition", "GOOD")
                put("quantity", "15.000")
                put("fefo_rank", 1)
            },
        )
        val taskRow = SnapshotRow(
            "count_task", taskId, null,
            buildJsonObject {
                put("task_id", taskId)
                put("task_number", "CNT-01")
                put("status", "COUNTING")
                putJsonArray("expectations") {
                    add(buildJsonObject {
                        put("stock_lot_id", lotId)
                        put("sku_id", "sku-01")
                        put("expected_qty", "15.000")
                    })
                }
            },
        )
        val policyRow = SnapshotRow(
            "inventory_policy", locationId, null,
            buildJsonObject {
                put("inventory.negative_stock.allowed", false)
                put("inventory.expiry.near_threshold_days", 30)
                put("inventory.transfer.transit_loss_limit_pct", "0.0200")
            },
        )
        val transferRow = SnapshotRow(
            "expected_transfer", transferId, null,
            buildJsonObject {
                put("transfer_id", transferId)
                put("transfer_no", "TRN-01")
                put("source_location_id", "loc-src")
                put("destination_location_id", locationId)
                putJsonArray("lines") {
                    add(buildJsonObject {
                        put("line_number", 1)
                        put("sku_id", "sku-01")
                        put("quantity", "50.000")
                    })
                }
            },
        )

        val state = SnapshotState(
            version = 5L,
            live = mapOf(
                "lot" to mapOf(lotId to lotRow),
                "count_task" to mapOf(taskId to taskRow),
                "inventory_policy" to mapOf(locationId to policyRow),
                "expected_transfer" to mapOf(transferId to transferRow),
            ),
            held = emptyList(),
        )

        EncryptedJvmDatabase.open(file, key).use { driver ->
            val store = SqlTillStore(driver)
            store.saveSnapshot(state)
        }

        EncryptedJvmDatabase.open(file, key).use { driver ->
            val store = SqlTillStore(driver)
            val reloaded = store.loadSnapshot()
            assertEquals(5L, reloaded.version)

            val lots = reloaded.table("lot")
            assertEquals(1, lots.size)
            val lot = lots.first()
            assertEquals(lotId, lot.rowId)
            assertEquals("15.000", lot.data?.get("quantity")?.jsonPrimitive?.content)
            assertEquals("1", lot.data?.get("fefo_rank")?.jsonPrimitive?.content)

            val tasks = reloaded.table("count_task")
            assertEquals(1, tasks.size)
            val task = tasks.first()
            assertEquals(taskId, task.rowId)
            assertEquals("COUNTING", task.data?.get("status")?.jsonPrimitive?.content)
            assertEquals(1, task.data?.get("expectations")?.jsonArray?.size)

            val policies = reloaded.table("inventory_policy")
            assertEquals(1, policies.size)
            val policy = policies.first()
            assertEquals(locationId, policy.rowId)
            assertEquals("false", policy.data?.get("inventory.negative_stock.allowed")?.jsonPrimitive?.content)
            assertEquals("30", policy.data?.get("inventory.expiry.near_threshold_days")?.jsonPrimitive?.content)

            val transfers = reloaded.table("expected_transfer")
            assertEquals(1, transfers.size)
            val transfer = transfers.first()
            assertEquals(transferId, transfer.rowId)
            assertEquals("TRN-01", transfer.data?.get("transfer_no")?.jsonPrimitive?.content)
            assertEquals(1, transfer.data?.get("lines")?.jsonArray?.size)
        }
    }

    private val session = SessionRecord("s1", "op", "Nimali", LocalDate(2026, 9, 29), 1_790_000_000, Money.parse("2000"))
    private val receipt = IssuedReceipt(
        documentId = "d1", sessionId = "s1", number = 1, numberDisplay = "S01-T2-1", issuedAt = 1_790_000_100,
        businessDate = LocalDate(2026, 9, 29), operatorUserId = "op", operatorName = "Nimali",
        lines = listOf(ReceiptLine(1, "sku", "Rice 5kg", "සහල් 5kg", "EA", Qty.of(1), Money.parse("1250"), Money.parse("1250"))),
        gross = Money.parse("1250"), tendered = Money.parse("1500"), change = Money.parse("250"), contentHash = "h", deviceSeq = 2,
    )
}
