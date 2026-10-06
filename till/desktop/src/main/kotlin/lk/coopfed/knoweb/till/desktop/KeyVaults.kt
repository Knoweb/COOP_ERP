package lk.coopfed.knoweb.till.desktop

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.HexFormat
import lk.coopfed.knoweb.till.core.port.KeyVault

/**
 * The till's database key cannot be had: missing beside an existing database, or damaged. The till
 * stops with [message] rather than make a new key that could not open the old database (TWK-08).
 * Doc 32 section 8 covers what the office does (a lost outbox: quarantine, the audited sequence
 * reset).
 */
class KeyVaultRefusal(message: String) : IllegalStateException(message)

/** A fresh 256-bit database key, as hex. */
private fun newKey(): String = HexFormat.of().formatHex(ByteArray(32).also { SecureRandom().nextBytes(it) })

/** A key as the vaults store it: 64 hex characters, or nothing usable. */
private fun checkedKey(key: String): String {
    val trimmed = key.trim()
    if (trimmed.length != 64 || trimmed.any { it !in "0123456789abcdefABCDEF" }) throw corrupt()
    return trimmed
}

private fun corrupt() = KeyVaultRefusal("The database key for this till is damaged; the office must re-enrol this PC")

private fun missing() = KeyVaultRefusal("The database key for this till is missing; the office must re-enrol this PC")

/**
 * Writes [bytes] to [file] by a temporary file in the same folder moved over it in one step, so a
 * crash leaves either no key file or a whole one, never an empty one. [restricted] creates the
 * temporary file readable only by the till's user (mode 600) where the file system has modes.
 */
private fun writeAtomically(file: Path, bytes: ByteArray, restricted: Boolean) {
    val folder = file.parent
    Files.createDirectories(folder)
    val posix = restricted && folder.fileSystem.supportedFileAttributeViews().contains("posix")
    val temp = if (posix) {
        Files.createTempFile(folder, file.fileName.toString(), ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } else {
        Files.createTempFile(folder, file.fileName.toString(), ".tmp")
    }
    try {
        Files.write(temp, bytes)
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(temp)
    }
}

/**
 * Windows: the database key is sealed with DPAPI for the till's Windows user (research report
 * section 6), so the file next to the database is useless on another account or another PC.
 *
 * DPAPI is given an application constant as its optional entropy. It does not stop a program that
 * runs as the same Windows user and knows the constant: it stops another program of that user from
 * unsealing the blob by accident or by a generic "unprotect every blob" sweep.
 */
class WindowsDpapiKeyVault(private val home: Path, private val database: Path = home.resolve("till.db")) : KeyVault {
    override fun databaseKey(): String {
        val file = home.resolve("database.key.dpapi")
        if (Files.exists(file)) {
            val sealed = Files.readAllBytes(file)
            if (sealed.isEmpty()) throw corrupt()
            val clear = try {
                Crypt32Util.cryptUnprotectData(sealed, ENTROPY, 0, null)
            } catch (e: Exception) {
                // A key sealed by the trial before the entropy was added.
                try {
                    Crypt32Util.cryptUnprotectData(sealed)
                } catch (again: Exception) {
                    throw corrupt()
                }
            }
            return checkedKey(String(clear, Charsets.UTF_8))
        }
        if (Files.exists(database)) throw missing()
        val key = newKey()
        val sealed = Crypt32Util.cryptProtectData(key.toByteArray(Charsets.UTF_8), ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "", null)
        writeAtomically(file, sealed, restricted = false)
        return key
    }

    private companion object {
        val ENTROPY = "lk.coopfed.knoweb.till/database-key/v1".toByteArray(Charsets.UTF_8)
    }
}

/**
 * Linux: a key file readable only by the till's user (mode 600). Weaker than DPAPI: root, or a
 * copy of the disk, can read it. A TPM-sealed key replaces it when the Linux kiosk is built
 * (research report section 6).
 */
class PrivateFileKeyVault(private val home: Path, private val database: Path = home.resolve("till.db")) : KeyVault {
    override fun databaseKey(): String {
        val file = home.resolve("database.key")
        if (Files.exists(file)) return checkedKey(Files.readString(file))
        if (Files.exists(database)) throw missing()
        val key = newKey()
        writeAtomically(file, key.toByteArray(Charsets.US_ASCII), restricted = true)
        return key
    }
}
