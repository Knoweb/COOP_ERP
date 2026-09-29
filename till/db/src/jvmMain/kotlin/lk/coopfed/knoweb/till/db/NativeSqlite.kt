package lk.coopfed.knoweb.till.db

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The SQLite driver unpacks its native library into the temp folder under a new name at every
 * start, and Windows scans each new DLL before loading it: seconds on a shop PC. The till keeps one
 * copy per driver version in its own folder instead and points the driver at it.
 */
object NativeSqlite {

    fun useInstalledCopy(home: Path) {
        val os = System.getProperty("os.name").lowercase()
        val arch = when (System.getProperty("os.arch")) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> return
        }
        val (folder, name) = when {
            os.contains("win") -> "Windows" to "sqlitejdbc.dll"
            os.contains("linux") -> "Linux" to "libsqlitejdbc.so"
            else -> return
        }
        val resource = "/org/sqlite/native/$folder/$arch/$name"
        val version = org.sqlite.SQLiteJDBCLoader::class.java.`package`?.implementationVersion ?: "unknown"
        val target = home.resolve("native").resolve(version).resolve(name)
        if (!Files.exists(target)) {
            val stream = org.sqlite.SQLiteJDBCLoader::class.java.getResourceAsStream(resource) ?: return
            Files.createDirectories(target.parent)
            val partial = target.resolveSibling("$name.part")
            stream.use { Files.copy(it, partial, StandardCopyOption.REPLACE_EXISTING) }
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
        }
        System.setProperty("org.sqlite.lib.path", target.parent.toString())
        System.setProperty("org.sqlite.lib.name", name)
    }
}
