package lk.coopfed.knoweb.till.desktop

import com.sun.jna.platform.win32.Crypt32Util
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.HexFormat
import lk.coopfed.knoweb.till.core.port.KeyVault

/** A fresh 256-bit database key, as hex. */
private fun newKey(): String = HexFormat.of().formatHex(ByteArray(32).also { SecureRandom().nextBytes(it) })

/**
 * Windows: the database key is sealed with DPAPI for the till's Windows user (research report
 * section 6), so the file next to the database is useless on another account or another PC.
 */
class WindowsDpapiKeyVault(private val home: Path) : KeyVault {
    override fun databaseKey(): String {
        val file = home.resolve("database.key.dpapi")
        if (Files.exists(file)) {
            return String(Crypt32Util.cryptUnprotectData(Files.readAllBytes(file)), Charsets.UTF_8)
        }
        val key = newKey()
        Files.createDirectories(home)
        Files.write(file, Crypt32Util.cryptProtectData(key.toByteArray(Charsets.UTF_8)))
        return key
    }
}

/**
 * Linux: a key file readable only by the till's user (mode 600). Weaker than DPAPI: root, or a
 * copy of the disk, can read it. A TPM-sealed key replaces it when the Linux kiosk is built
 * (research report section 6).
 */
class PrivateFileKeyVault(private val home: Path) : KeyVault {
    override fun databaseKey(): String {
        val file = home.resolve("database.key")
        if (Files.exists(file)) return Files.readString(file).trim()
        Files.createDirectories(home)
        val key = newKey()
        // Created with mode 600 from the start, never readable by others even for a moment.
        val posix = home.fileSystem.supportedFileAttributeViews().contains("posix")
        if (posix) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }
        Files.writeString(file, key)
        return key
    }
}
