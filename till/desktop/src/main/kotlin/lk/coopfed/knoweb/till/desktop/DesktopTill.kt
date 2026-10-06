package lk.coopfed.knoweb.till.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
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
import lk.coopfed.knoweb.till.sync.JvmGzip

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
        val vault = if (DesktopConfig.isWindows) WindowsDpapiKeyVault(config.home, config.database) else PrivateFileKeyVault(config.home, config.database)
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
        config.caFile?.let { ca -> engine { config { sslContext(trusting(ca)) } } }
    }

    val service = TillService(
        store = SqlTillStore(driver),
        central = HttpCentral(http, ids, { Clock.System.now() }, JvmGzip, config.tokenEndpointOverride),
        verifier = SnapshotVerifier(JdkEd25519Verifier),
        pins = Argon2PinVerifier,
        clock = object : TillClock {
            override fun now() = Clock.System.now()
            override val zone = this@DesktopTill.zone
            override fun elapsedMillis(): Long = System.nanoTime() / 1_000_000
        },
        ids = ids,
        appVersion = APP_VERSION,
        localPrices = config.priceBook(),
        trialCashierAllowed = config.trialCashier,
        tokenEndpointSetLocally = config.tokenEndpointOverride != null,
    )

    override fun close() {
        http.close()
        driver.close()
    }
}

/**
 * TLS that trusts the system's certificate authorities and the ones in [caFile] (PEM), for a
 * server with a private certificate (COOP_TILL_CA_FILE; decision D-1). Never a "trust all".
 */
internal fun trusting(caFile: Path): SSLContext {
    val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()
    val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    system.acceptedIssuers.forEachIndexed { i, cert -> store.setCertificateEntry("system-$i", cert) }
    val added = Files.newInputStream(caFile).use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
    require(added.isNotEmpty()) { "COOP_TILL_CA_FILE $caFile holds no certificate" }
    added.forEachIndexed { i, cert -> store.setCertificateEntry("coop-till-ca-$i", cert) }
    val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }.trustManagers
    return SSLContext.getInstance("TLS").apply { init(null, managers, null) }
}
