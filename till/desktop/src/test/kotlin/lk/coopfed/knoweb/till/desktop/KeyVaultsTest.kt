package lk.coopfed.knoweb.till.desktop

import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The key file is written whole or not at all, and never replaced silently (TWK-08). */
class KeyVaultsTest {

    private val home = Files.createTempDirectory("till-keys")

    private fun vaults() = buildList {
        add(PrivateFileKeyVault(home))
        if (DesktopConfig.isWindows) add(WindowsDpapiKeyVault(home))
    }

    @Test
    fun aKeyIsMadeOnceWithNoTemporaryFileLeftAndReadBackTheSame() {
        for (vault in vaults()) {
            val key = vault.databaseKey()
            assertEquals(64, key.length)
            assertEquals(key, vault.databaseKey())
        }
        assertTrue(home.listDirectoryEntries().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun aMissingKeyBesideAnExistingDatabaseIsRefusedNotReplaced() {
        Files.writeString(home.resolve("till.db"), "an existing database")
        for (vault in vaults()) {
            val refusal = assertFailsWith<KeyVaultRefusal> { vault.databaseKey() }
            assertEquals("The database key for this till is missing; the office must re-enrol this PC", refusal.message)
        }
        assertTrue(home.listDirectoryEntries().none { it.name.startsWith("database.key") })
    }

    @Test
    fun anEmptyOrShortKeyFileIsDamaged() {
        val file = home.resolve("database.key")
        for (content in listOf("", "0f0f", "zz".repeat(32))) {
            Files.writeString(file, content)
            val refusal = assertFailsWith<KeyVaultRefusal> { PrivateFileKeyVault(home).databaseKey() }
            assertEquals("The database key for this till is damaged; the office must re-enrol this PC", refusal.message)
        }
        if (DesktopConfig.isWindows) {
            Files.write(home.resolve("database.key.dpapi"), ByteArray(0))
            assertFailsWith<KeyVaultRefusal> { WindowsDpapiKeyVault(home).databaseKey() }
        }
    }
}
