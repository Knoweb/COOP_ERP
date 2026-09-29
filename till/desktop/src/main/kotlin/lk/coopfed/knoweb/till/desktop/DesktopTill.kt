package lk.coopfed.knoweb.till.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import java.nio.file.Files
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import lk.coopfed.knoweb.till.core.TillService
import lk.coopfed.knoweb.till.core.id.UuidV7
import lk.coopfed.knoweb.till.core.port.TillClock
import lk.coopfed.knoweb.till.core.snapshot.SnapshotVerifier
import lk.coopfed.knoweb.till.db.EncryptedJvmDatabase
import lk.coopfed.knoweb.till.db.NativeSqlite
import lk.coopfed.knoweb.till.db.SqlTillStore
import lk.coopfed.knoweb.till.sync.Argon2PinVerifier
import lk.coopfed.knoweb.till.sync.HttpCentral
import lk.coopfed.knoweb.till.sync.JdkEd25519Verifier

/** The version every target reports (gradle.properties till.version, in the jar's manifest). */
val APP_VERSION: String = DesktopTill::class.java.`package`?.implementationVersion ?: "0.1.0"

/**
 * The desktop's wiring, in one place for the window and the opt-in stack test: the database
 * opened with the key from the OS key vault, the HTTP sync client, the JDK's Ed25519, Argon2id
 * from BouncyCastle, the shop's clock. Everything else is the shared TillService.
 */
class DesktopTill(val config: DesktopConfig = DesktopConfig()) : AutoCloseable {

    val zone: TimeZone = TimeZone.of("Asia/Colombo")
    private val ids = UuidV7({ System.currentTimeMillis() })
    /** How long the key vault and the database took to open, for the start-up measurements. */
    var openTimings: String = ""
        private set
    private val driver = run {
        Files.createDirectories(config.home)
        val t0 = System.nanoTime()
        val vault = if (DesktopConfig.isWindows) WindowsDpapiKeyVault(config.home) else PrivateFileKeyVault(config.home)
        val key = vault.databaseKey()
        val t1 = System.nanoTime()
        NativeSqlite.useInstalledCopy(config.home)
        val t2 = System.nanoTime()
        EncryptedJvmDatabase.open(config.database, key).also {
            val t3 = System.nanoTime()
            openTimings = "key vault ${(t1 - t0) / 1_000_000} ms, native library ${(t2 - t1) / 1_000_000} ms, database ${(t3 - t2) / 1_000_000} ms"
        }
    }
    private val http = HttpClient(Java) {
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 30_000
        }
    }

    val service = TillService(
        store = SqlTillStore(driver),
        central = HttpCentral(http, ids, { Clock.System.now() }, config.tokenEndpointOverride),
        verifier = SnapshotVerifier(JdkEd25519Verifier),
        pins = Argon2PinVerifier,
        clock = object : TillClock {
            override fun now() = Clock.System.now()
            override val zone = this@DesktopTill.zone
        },
        ids = ids,
        appVersion = APP_VERSION,
        localPrices = config.priceBook(),
    )

    override fun close() {
        http.close()
        driver.close()
    }
}
