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
import lk.coopfed.knoweb.till.core.model.Anomaly
import lk.coopfed.knoweb.till.core.model.IssuedReceipt
import lk.coopfed.knoweb.till.core.model.OutboxEntry
import lk.coopfed.knoweb.till.core.model.ReceiptLine
import lk.coopfed.knoweb.till.core.model.SessionRecord
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.money.Qty

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

    private val session = SessionRecord("s1", "op", "Nimali", LocalDate(2026, 9, 29), 1_790_000_000, Money.parse("2000"))
    private val receipt = IssuedReceipt(
        documentId = "d1", sessionId = "s1", number = 1, numberDisplay = "S01-T2-1", issuedAt = 1_790_000_100,
        businessDate = LocalDate(2026, 9, 29), operatorUserId = "op", operatorName = "Nimali",
        lines = listOf(ReceiptLine(1, "sku", "Rice 5kg", "සහල් 5kg", "EA", Qty.of(1), Money.parse("1250"), Money.parse("1250"))),
        gross = Money.parse("1250"), tendered = Money.parse("1500"), change = Money.parse("250"), contentHash = "h", deviceSeq = 2,
    )
}
