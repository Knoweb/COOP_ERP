package lk.coopfed.knoweb.till.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import lk.coopfed.knoweb.till.render.SkiaReceiptRasteriser
import lk.coopfed.knoweb.till.ui.TillApp
import lk.coopfed.knoweb.till.ui.TillController

/**
 * The desktop till's window around the shared screens.
 *
 * COOP_TILL_MEASURE=<seconds> closes the till that many seconds after the first frame and prints
 * the start-up time and memory, for the trial's measurements (till/README.md).
 */
fun main() {
    val jvmStart = ManagementFactory.getRuntimeMXBean().startTime
    fun since() = System.currentTimeMillis() - jvmStart
    val atMain = since()
    val till = DesktopTill()
    val config = till.config
    val atDatabase = since()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val controller = TillController(till.service, config.printer(), SkiaReceiptRasteriser(), scope, till.zone, config.paperDots)
    val atWired = since()
    val measureSeconds = System.getenv("COOP_TILL_MEASURE")?.toLongOrNull()
    if (measureSeconds != null) log(config, "main() at $atMain ms, database open at $atDatabase ms (${till.openTimings}), wired at $atWired ms")

    application {
        val state = rememberWindowState(size = DpSize(1280.dp, 800.dp))
        Window(onCloseRequest = { till.close(); exitApplication() }, title = "COOP ERP till $APP_VERSION", state = state) {
            TillApp(controller, config.enrolDefaults())
            LaunchedEffect(Unit) {
                val startedMs = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().startTime
                log(config, "first frame $startedMs ms after the JVM started; heap used ${usedHeapMb()} MB")
                if (measureSeconds != null) {
                    delay(measureSeconds * 1000)
                    log(config, "after $measureSeconds s: heap used ${usedHeapMb()} MB, committed ${Runtime.getRuntime().totalMemory() / 1_048_576} MB")
                    till.close()
                    exitProcess(0)
                }
            }
        }
    }
}

private fun log(config: DesktopConfig, text: String) {
    val line = "${LocalDateTime.now()} $text"
    println(line)
    runCatching {
        Files.writeString(config.home.resolve("startup.log"), line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}

private fun usedHeapMb(): Long = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576
