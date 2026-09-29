package lk.coopfed.knoweb.till.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.readLines
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.receipt.MonoBitmap
import lk.coopfed.knoweb.till.peripherals.PrinterPort
import lk.coopfed.knoweb.till.peripherals.jvm.NetworkPrinter
import lk.coopfed.knoweb.till.peripherals.jvm.PreviewFolderPrinter
import lk.coopfed.knoweb.till.ui.EnrolDefaults

/**
 * Where the desktop till keeps its files and what it talks to. Each setting is an environment
 * variable, so a shop PC is configured once by whoever installs it:
 *
 * - COOP_TILL_HOME: the data folder (default %LOCALAPPDATA%\CoopTill on Windows, ~/.local/share/coop-till on Linux)
 * - COOP_TILL_PRINTER: "tcp://192.168.1.50:9100" for a network printer; empty for the preview folder
 * - COOP_TILL_PAPER: 80 (default) or 58 mm
 * - COOP_TILL_TOKEN_ENDPOINT: the identity provider's token URL when the till reaches it by another address
 */
class DesktopConfig(private val env: Map<String, String> = System.getenv()) {

    val home: Path = env["COOP_TILL_HOME"]?.let { Paths.get(it) } ?: defaultHome()
    val database: Path get() = home.resolve("till.db")
    val previewFolder: Path get() = home.resolve("print-preview")
    val paperDots: Int = if (env["COOP_TILL_PAPER"] == "58") MonoBitmap.DOTS_58MM else MonoBitmap.DOTS_80MM
    val tokenEndpointOverride: String? = env["COOP_TILL_TOKEN_ENDPOINT"]?.takeIf { it.isNotBlank() }

    fun printer(): PrinterPort {
        val setting = env["COOP_TILL_PRINTER"]?.trim().orEmpty()
        if (setting.startsWith("tcp://")) {
            val address = setting.removePrefix("tcp://")
            return NetworkPrinter(address.substringBefore(':'), address.substringAfter(':', "9100").toInt())
        }
        return PreviewFolderPrinter(previewFolder)
    }

    /**
     * The trial's price book (see the till README): "sku_id or barcode,price" per line, written by
     * the devEnrolmentCode task from the shop's published shelf price list.
     */
    fun priceBook(): Map<String, Money> {
        val file = home.resolve("prices.csv")
        if (!file.exists()) return emptyMap()
        return file.readLines().mapNotNull { line ->
            val parts = line.split(',')
            if (parts.size < 2 || line.startsWith("#")) null else parts[0].trim() to Money.parse(parts[1].trim())
        }.toMap()
    }

    /** What the enrol form shows first: env settings, then the file the devEnrolmentCode task leaves. */
    fun enrolDefaults(): EnrolDefaults {
        val prefill = Properties()
        val file = home.resolve("enrol-prefill.properties")
        if (file.exists()) Files.newBufferedReader(file).use { prefill.load(it) }
        fun pick(envName: String, key: String, default: String) = env[envName] ?: prefill.getProperty(key) ?: default
        return EnrolDefaults(
            serverUrl = pick("COOP_TILL_SERVER", "server", "http://localhost:8080"),
            deviceId = pick("COOP_TILL_DEVICE_ID", "device_id", ""),
            code = pick("COOP_TILL_CODE", "code", ""),
            hardwareSerial = pick("COOP_TILL_SERIAL", "hardware_serial", "DESKTOP-TRIAL-S01"),
        )
    }

    companion object {
        val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")

        fun defaultHome(): Path = if (isWindows) {
            Paths.get(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "CoopTill")
        } else {
            Paths.get(System.getenv("XDG_DATA_HOME") ?: (System.getProperty("user.home") + "/.local/share"), "coop-till")
        }
    }
}
