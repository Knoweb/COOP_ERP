package lk.coopfed.knoweb.till.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import java.nio.file.Path
import java.util.HexFormat
import org.sqlite.mc.SQLiteMCSqlCipherConfig

/**
 * Opens the till's database on Windows and Linux: one SQLite file encrypted in the SQLCipher 4
 * format (sqlite-jdbc-crypt, SQLite3MultipleCiphers), the same format SQLCipher for Android writes,
 * so a database can be read by either. The key comes from the platform's key vault, never from a
 * file next to the database.
 */
object EncryptedJvmDatabase {

    /** @param key the database key, 32 bytes as hex */
    fun open(file: Path, key: String): SqlDriver {
        file.parent?.let { Files.createDirectories(it) }
        // The key is 256 random bits from the key vault, not a pass phrase, so it is used raw: no
        // PBKDF2 stretching (SQLCipher's 256,000 rounds cost about two seconds at every start on a
        // shop PC and add nothing to a random key).
        val raw = HexFormat.of().parseHex(key)
        require(raw.size == 32) { "The database key must be 32 bytes as hex" }
        val properties = SQLiteMCSqlCipherConfig.getV4Defaults().withRawUnsaltedKey(raw).build().toProperties()
        // Readers wait for a writer instead of failing at once (the upload runs beside the sale).
        properties["busy_timeout"] = "5000"
        properties["journal_mode"] = "WAL"
        return JdbcSqliteDriver("jdbc:sqlite:${file.toAbsolutePath()}", properties, TillDatabase.Schema)
    }
}
