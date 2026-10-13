package lk.coopfed.knoweb.till.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import java.util.HexFormat
import org.sqlite.mc.SQLiteMCSqlCipherConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import lk.coopfed.knoweb.till.core.model.IssuedReceipt
import lk.coopfed.knoweb.till.core.model.OutboxEntry
import lk.coopfed.knoweb.till.core.model.ReceiptLine
import lk.coopfed.knoweb.till.core.model.SessionRecord
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.money.Qty

/**
 * Verification of the versioned schema migration scheme (TILL-PLAT-04, [T-01]):
 * - Database initialized using the old v1 schema and seeded with existing records
 *   (receipts, outbox items, session, settings, anomaly, snapshot rows).
 * - Upgraded cleanly to the new schema version (v2).
 * - Verifies zero existing data is lost or corrupted.
 * - Verifies all 8 newly added tables (document_lines, tenders, state_history,
 *   stock_movements, audit, cash_movements, parked_sales, lot_cache) are operational.
 */
class TillDatabaseMigrationTest {

    @Test
    fun migrationFromV1PreservesAllExistingDataAndEnablesAllNewTables() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        // 1. Initialize with the old v1 schema (prior to TILL-PLAT-04)
        createOldV1Schema(driver)
        assertEquals(0L, TillDatabaseMigrator.currentVersion(driver))
        assertTrue(TillDatabaseMigrator.hasTable(driver, "setting"))
        assertTrue(TillDatabaseMigrator.hasTable(driver, "receipt"))
        assertTrue(TillDatabaseMigrator.hasTable(driver, "outbox"))
        assertTrue(TillDatabaseMigrator.hasTable(driver, "till_session"))
        assertTrue(TillDatabaseMigrator.hasTable(driver, "snapshot_row"))
        assertTrue(TillDatabaseMigrator.hasTable(driver, "anomaly"))

        // None of the new tables exist yet
        val newTables = listOf(
            "document_lines", "tenders", "state_history", "stock_movements",
            "audit", "cash_movements", "parked_sales", "lot_cache",
        )
        for (table in newTables) {
            assertFalse(TillDatabaseMigrator.hasTable(driver, table), "Table $table should not exist in v1")
        }

        // 2. Seed with existing records using the v1 schema
        val storeBefore = SqlTillStore(driver)
        storeBefore.transaction {
            storeBefore.putSetting("next_receipt_number", "42")
            storeBefore.putSetting("next_device_seq", "10")
            storeBefore.putSetting("business_date", "2026-10-12")
            storeBefore.putSetting("device", """{"deviceId":"DEV-01","locationId":"LOC-01"}""")
            storeBefore.saveSession(sampleSession)
            storeBefore.saveReceipt(sampleReceipt)
            storeBefore.appendOutbox(OutboxEntry(1, "evt-1", "receipt.issued.v1", """{"marker":"receipt-1"}"""))
            storeBefore.appendOutbox(OutboxEntry(2, "evt-2", "receipt.issued.v1", """{"marker":"receipt-2"}"""))
            storeBefore.acknowledgeUpTo(1, atMillis = 1_000)
        }

        // Also add raw rows for anomaly and snapshot_row
        driver.execute(null, """
            INSERT INTO snapshot_row(tbl, row_id, apply_from, data, held)
            VALUES ('sku', 'sku-01', '2026-10-12', '{"skuId":"sku-01","name":"Rice 5kg"}', 0)
        """.trimIndent(), 0)
        driver.execute(null, """
            INSERT INTO anomaly(kind, device_seq, event_id, reason, detail, noted_at, seen)
            VALUES ('QUARANTINED', 1, 'evt-1', 'HASH', 'hash mismatch', 1000, 0)
        """.trimIndent(), 0)

        // 3. Run migration to new schema version
        val migrated = TillDatabaseMigrator.migrate(driver)
        assertTrue(migrated, "TillDatabaseMigrator.migrate should report migration applied")
        assertEquals(TillDatabaseMigrator.TARGET_VERSION, TillDatabaseMigrator.currentVersion(driver))
        assertEquals(2L, TillDatabaseMigrator.currentVersion(driver))

        // Running migration again is idempotent
        assertFalse(TillDatabaseMigrator.migrate(driver), "Second migration should be a no-op")

        // 4. Verify ZERO existing data was lost or corrupted
        val storeAfter = SqlTillStore(driver)
        assertEquals("42", storeAfter.setting("next_receipt_number"))
        assertEquals("10", storeAfter.setting("next_device_seq"))
        assertEquals("2026-10-12", storeAfter.setting("business_date"))
        assertEquals("""{"deviceId":"DEV-01","locationId":"LOC-01"}""", storeAfter.setting("device"))

        val session = storeAfter.session("s1")
        assertNotNull(session)
        assertEquals(sampleSession, session)
        assertTrue(session.isOpen)
        assertEquals(Money.parse("2000"), session.floatAmount)

        val receipts = storeAfter.receiptsOfSession("s1")
        assertEquals(1, receipts.size)
        assertEquals(sampleReceipt, receipts[0])
        assertEquals(41L, storeAfter.maxReceiptNumber())

        assertEquals(1L, storeAfter.pendingCount())
        assertEquals(listOf(2L), storeAfter.pendingOutbox(10).map { it.deviceSeq })
        assertTrue(storeAfter.outboxHolds(1L), "Acknowledged outbox entry must be kept")
        assertTrue(storeAfter.outboxHolds(2L), "Pending outbox entry must be kept")
        assertEquals(2L, storeAfter.maxOutboxSeq())

        val snapshotState = storeAfter.loadSnapshot()
        assertTrue(snapshotState.live.containsKey("sku"))
        assertNotNull(snapshotState.live["sku"]?.get("sku-01"))

        assertEquals(1L, storeAfter.unseenAnomalyCount())
        assertEquals(1, storeAfter.anomalies(10).size)
        assertEquals(1L, storeAfter.quarantinedAmong(listOf(1L)))

        // 5. Verify ALL newly added tables exist and are operational
        for (table in newTables) {
            assertTrue(TillDatabaseMigrator.hasTable(driver, table), "Table $table must exist after migration")
        }

        val allTables = TillDatabase.allTableNames()
        assertTrue(allTables.containsAll(newTables))

        val q = storeAfter.tillQueries

        // 5.1 document_lines
        q.insertDocumentLine(
            line_id = "line-1",
            document_id = "d1",
            line_no = 1,
            sku_id = "sku-01",
            batch_id = "batch-01",
            uom_code = "EA",
            qty = "2.000",
            unit_price = "1250.00",
            mrp_applied = "1300.00",
            control_price_applied = null,
            cap_reason = null,
            discount_rule_id = null,
            discount_amount = "0.00",
            tax_rate_percent = "0.00",
            tax_amount = "0.00",
            line_total = "2500.00",
            unit_cost_at_issue = "1000.00",
            loss_category = null,
            reference_line_id = null,
            negative_ack = 0,
            picked = 0,
            weight_kg = null,
        )
        val lines = q.selectDocumentLinesByDocument("d1").executeAsList()
        assertEquals(1, lines.size)
        assertEquals("line-1", lines[0].line_id)
        assertEquals("2500.00", lines[0].line_total)

        // 5.2 tenders
        q.insertTender(
            tender_id = "tender-1",
            document_id = "d1",
            seq = 1,
            kind = "CASH",
            amount = "3000.00",
            reference = null,
            customer_account_id = null,
            change_given = "500.00",
            offline = 0,
        )
        val tenders = q.selectTendersByDocument("d1").executeAsList()
        assertEquals(1, tenders.size)
        assertEquals("CASH", tenders[0].kind)
        assertEquals("3000.00", tenders[0].amount)

        // 5.3 state_history
        q.insertStateHistory(
            history_id = "hist-1",
            document_id = "d1",
            from_status = "DRAFT",
            to_status = "ISSUED",
            occurred_at = 1_790_000_100L,
            occurred_local = "2026-10-12T14:30:00",
            actor_user_id = "op",
            reason_code = null,
            reason_text = null,
        )
        val history = q.selectStateHistoryByDocument("d1").executeAsList()
        assertEquals(1, history.size)
        assertEquals("ISSUED", history[0].to_status)

        // 5.4 stock_movements
        q.insertStockMovement(
            movement_id = "sm-1",
            movement_seq = 1,
            location_id = "LOC-01",
            sku_id = "sku-01",
            batch_id = "batch-01",
            lot_id = "lot-01",
            condition = "GOOD",
            qty_delta = "-2.000",
            movement_type = "SALE",
            unit_cost_at_movement = "1000.00",
            document_id = "d1",
            document_line_id = "line-1",
            occurred_at = 1_790_000_100L,
            occurred_local = "2026-10-12T14:30:00",
            operator_user_id = "op",
            device_id = "DEV-01",
        )
        val movements = q.selectStockMovementsByDocument("d1").executeAsList()
        assertEquals(1, movements.size)
        assertEquals("-2.000", movements[0].qty_delta)

        // 5.5 audit
        q.insertAuditEvent(
            audit_id = "aud-1",
            event_type_code = "pos.receipt.issue",
            occurred_at = 1_790_000_100L,
            occurred_local = "2026-10-12T14:30:00",
            owner_entity_id = "ENT-01",
            location_id = "LOC-01",
            actor_user_id = "op",
            device_id = "DEV-01",
            till_position_id = "POS-01",
            subject_table = "receipt",
            subject_id = "d1",
            document_id = "d1",
            before_state = null,
            after_state = "ISSUED",
            reason_code = null,
            reason_text = null,
            device_seq = 2L,
            correlation_id = null,
        )
        val auditEvents = q.selectAuditEvents(10).executeAsList()
        assertEquals(1, auditEvents.size)
        assertEquals("pos.receipt.issue", auditEvents[0].event_type_code)

        // 5.6 cash_movements
        q.insertCashMovement(
            id = "cm-1",
            session_id = "s1",
            kind = "FLOAT",
            amount = "2000.00",
            at = 1_790_000_000L,
            operator_user_id = "op",
            reason = "Opening float",
        )
        val cashMovements = q.selectCashMovementsBySession("s1").executeAsList()
        assertEquals(1, cashMovements.size)
        assertEquals("FLOAT", cashMovements[0].kind)
        assertEquals("2000.00", cashMovements[0].amount)

        // 5.7 parked_sales
        q.insertParkedSale(
            id = "parked-1",
            session_id = "s1",
            lines_json = """[{"skuId":"sku-01","qty":1}]""",
            parked_at = 1_790_000_050L,
            operator_user_id = "op",
            customer_id = null,
            notes = "Customer left to get cash",
        )
        val parked = q.selectParkedSalesBySession("s1").executeAsList()
        assertEquals(1, parked.size)
        assertEquals("parked-1", parked[0].id)
        q.deleteParkedSale("parked-1")
        assertEquals(0, q.selectParkedSalesBySession("s1").executeAsList().size)

        // 5.8 lot_cache
        q.insertLotCache(
            lot_id = "lot-01",
            sku_id = "sku-01",
            batch_id = "batch-01",
            location_id = "LOC-01",
            cost_price = "1000.00",
            selling_price = "1250.00",
            mrp = "1300.00",
            available_qty = "50.000",
            expiry_date = "2027-01-01",
            condition = "GOOD",
            updated_at = 1_790_000_000L,
        )
        val lots = q.selectLotCacheBySku("sku-01").executeAsList()
        assertEquals(1, lots.size)
        assertEquals("50.000", lots[0].available_qty)
        q.deleteLotCache()
        assertEquals(0, q.selectLotCacheBySku("sku-01").executeAsList().size)

        driver.close()
    }

    @Test
    fun encryptedDatabaseUpgradesCleanlyViaEncryptedJvmDatabaseOpen() {
        val dir = Files.createTempDirectory("till-db-mig-test")
        val file = dir.resolve("till.db")
        val key = "1a".repeat(32)

        // 1. Create database file directly with v1 schema and seed data
        val raw = HexFormat.of().parseHex(key)
        val properties = SQLiteMCSqlCipherConfig.getV4Defaults().withRawUnsaltedKey(raw).build().toProperties()
        properties["journal_mode"] = "WAL"
        val initialDriver = JdbcSqliteDriver("jdbc:sqlite:${file.toAbsolutePath()}", properties)
        createOldV1Schema(initialDriver)
        initialDriver.execute(null, "INSERT INTO setting(key, value) VALUES ('seeded_key', 'seeded_val')", 0)
        initialDriver.execute(null, """
            INSERT INTO receipt(document_id, session_id, number, json)
            VALUES ('doc-pre', 's-pre', 10, '{"documentId":"doc-pre"}')
        """.trimIndent(), 0)
        initialDriver.close()

        // 2. Open via EncryptedJvmDatabase.open which applies TillDatabaseMigrator.migrate automatically
        EncryptedJvmDatabase.open(file, key).use { driver ->
            assertEquals(2L, TillDatabaseMigrator.currentVersion(driver))
            val store = SqlTillStore(driver)
            assertEquals("seeded_val", store.setting("seeded_key"))
            assertEquals(10L, store.maxReceiptNumber())

            // Verify new tables are immediately operational
            assertTrue(TillDatabaseMigrator.hasTable(driver, "document_lines"))
            assertTrue(TillDatabaseMigrator.hasTable(driver, "tenders"))
            assertTrue(TillDatabaseMigrator.hasTable(driver, "state_history"))
            assertTrue(TillDatabaseMigrator.hasTable(driver, "stock_movements"))
            assertTrue(TillDatabaseMigrator.hasTable(driver, "audit"))
            assertTrue(TillDatabaseMigrator.hasTable(driver, "cash_movements"))
            assertTrue(TillDatabaseMigrator.hasTable(driver, "parked_sales"))
            assertTrue(TillDatabaseMigrator.hasTable(driver, "lot_cache"))
        }
    }

    @Test
    fun freshDatabaseInitializesDirectlyToLatestVersion() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        assertEquals(0L, TillDatabaseMigrator.currentVersion(driver))
        assertFalse(TillDatabaseMigrator.hasTable(driver, "setting"))

        // Run migrator on clean database
        assertTrue(TillDatabaseMigrator.migrate(driver))
        assertEquals(2L, TillDatabaseMigrator.currentVersion(driver))

        // All 14 tables created
        for (table in TillDatabase.allTableNames()) {
            assertTrue(TillDatabaseMigrator.hasTable(driver, table), "Table $table must exist in fresh DB")
        }

        driver.close()
    }

    private fun createOldV1Schema(driver: JdbcSqliteDriver) {
        driver.execute(null, """
            CREATE TABLE setting (
                key TEXT NOT NULL PRIMARY KEY,
                value TEXT NOT NULL
            );
        """.trimIndent(), 0)

        driver.execute(null, """
            CREATE TABLE snapshot_row (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tbl TEXT NOT NULL,
                row_id TEXT NOT NULL,
                apply_from TEXT,
                data TEXT,
                held INTEGER NOT NULL
            );
        """.trimIndent(), 0)

        driver.execute(null, """
            CREATE TABLE outbox (
                device_seq INTEGER NOT NULL PRIMARY KEY,
                event_id TEXT NOT NULL UNIQUE,
                event_type TEXT NOT NULL,
                json TEXT NOT NULL,
                acked_at INTEGER
            );
        """.trimIndent(), 0)
        driver.execute(null, "CREATE INDEX outbox_pending ON outbox(acked_at, device_seq);", 0)

        driver.execute(null, """
            CREATE TABLE till_session (
                session_id TEXT NOT NULL PRIMARY KEY,
                is_open INTEGER NOT NULL,
                json TEXT NOT NULL
            );
        """.trimIndent(), 0)

        driver.execute(null, """
            CREATE TABLE receipt (
                document_id TEXT NOT NULL PRIMARY KEY,
                session_id TEXT NOT NULL,
                number INTEGER NOT NULL UNIQUE,
                json TEXT NOT NULL
            );
        """.trimIndent(), 0)
        driver.execute(null, "CREATE INDEX receipt_session ON receipt(session_id);", 0)

        driver.execute(null, """
            CREATE TABLE anomaly (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                device_seq INTEGER,
                event_id TEXT,
                reason TEXT,
                detail TEXT NOT NULL,
                noted_at INTEGER NOT NULL,
                seen INTEGER NOT NULL DEFAULT 0
            );
        """.trimIndent(), 0)
        driver.execute(null, "CREATE UNIQUE INDEX anomaly_fact ON anomaly(kind, device_seq);", 0)
    }

    private val sampleSession = SessionRecord("s1", "op", "Nimali", LocalDate(2026, 10, 12), 1_790_000_000, Money.parse("2000"))
    private val sampleReceipt = IssuedReceipt(
        documentId = "d1", sessionId = "s1", number = 41, numberDisplay = "S01-T2-41", issuedAt = 1_790_000_100,
        businessDate = LocalDate(2026, 10, 12), operatorUserId = "op", operatorName = "Nimali",
        lines = listOf(ReceiptLine(1, "sku", "Rice 5kg", "සහල් 5kg", "EA", Qty.of(1), Money.parse("1250"), Money.parse("1250"))),
        gross = Money.parse("1250"), tendered = Money.parse("1500"), change = Money.parse("250"), contentHash = "h", deviceSeq = 2,
    )
}
