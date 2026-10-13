package lk.coopfed.knoweb.till.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Manages schema migrations for the till's SQLite database.
 *
 * Tracks the schema version via `PRAGMA user_version`. For databases created prior
 * to version tracking (where `user_version` is 0 but baseline tables such as `setting` exist),
 * detects baseline version 1 and applies incremental migrations up to the current [TARGET_VERSION]
 * without any data loss.
 */
object TillDatabaseMigrator {

    val TARGET_VERSION: Long
        get() = TillDatabase.Schema.version

    /** Returns the current schema version recorded in `PRAGMA user_version`. */
    fun currentVersion(driver: SqlDriver): Long {
        return driver.executeQuery(null, "PRAGMA user_version", { cursor ->
            if (cursor.next().value) {
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            } else {
                QueryResult.Value(0L)
            }
        }, 0).value
    }

    /** Checks whether a table exists in SQLite. */
    fun hasTable(driver: SqlDriver, tableName: String): Boolean {
        return driver.executeQuery(
            null,
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='$tableName'",
            { cursor ->
                if (cursor.next().value) {
                    QueryResult.Value((cursor.getLong(0) ?: 0L) > 0L)
                } else {
                    QueryResult.Value(false)
                }
            },
            0,
        ).value
    }

    /**
     * Migrates the database to [TARGET_VERSION] if needed.
     * Preserves all existing data.
     *
     * @return true if a migration or schema creation took place, false if already at [TARGET_VERSION].
     */
    fun migrate(driver: SqlDriver): Boolean {
        val userVer = currentVersion(driver)
        if (userVer >= TARGET_VERSION) {
            return false
        }

        if (userVer == 0L) {
            val hasBaseline = hasTable(driver, "setting")
            if (!hasBaseline) {
                // Fresh, uninitialized database: create schema and stamp version.
                TillDatabase.Schema.create(driver)
                setVersion(driver, TARGET_VERSION)
                return true
            }

            // Database was created at v1 before version tracking was introduced.
            // Check if v2 tables are already present (e.g. if created with latest schema directly)
            if (hasTable(driver, "document_lines")) {
                setVersion(driver, TARGET_VERSION)
                return true
            }

            // Old v1 schema: apply migrations from 1 to TARGET_VERSION
            TillDatabase.Schema.migrate(driver, oldVersion = 1L, newVersion = TARGET_VERSION)
            setVersion(driver, TARGET_VERSION)
            return true
        }

        // Incremental migration from current version
        TillDatabase.Schema.migrate(driver, oldVersion = userVer, newVersion = TARGET_VERSION)
        setVersion(driver, TARGET_VERSION)
        return true
    }

    fun setVersion(driver: SqlDriver, version: Long) {
        driver.execute(null, "PRAGMA user_version = $version", 0)
    }
}
