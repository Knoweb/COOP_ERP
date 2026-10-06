package lk.coopfed.knoweb.till.peripherals.jvm

import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import lk.coopfed.knoweb.till.core.receipt.MonoBitmap
import lk.coopfed.knoweb.till.peripherals.EscPos
import lk.coopfed.knoweb.till.peripherals.PrintJob

class PrintersTest {

    private val image = MonoBitmap(16, 2).apply { this[3, 1] = true }

    @Test
    fun aNetworkPrinterReceivesTheWholeEscPosJob() = runBlocking {
        ServerSocket(0).use { server ->
            var received = ByteArray(0)
            val listener = thread { server.accept().use { received = it.getInputStream().readBytes() } }
            NetworkPrinter("127.0.0.1", server.localPort).print(PrintJob("receipt", image, kickDrawer = true))
            listener.join(5000)
            assertContentEquals(EscPos.job(image, kickDrawer = true), received)
        }
    }

    @Test
    fun withNoPrinterTheSlipGoesToAFolderAsPngAndBin() = runBlocking {
        val folder = Files.createTempDirectory("till-print")
        PreviewFolderPrinter(folder).print(PrintJob("S01-T2-1", image, kickDrawer = false))

        val files = folder.listDirectoryEntries().map { it.fileName.toString() }.sorted()
        assertEquals(listOf("bin", "png"), files.map { it.substringAfterLast('.') })
        val png = Png.read(folder.resolve(files[1]).toFile())
        assertEquals(true, png[3, 1])
        assertEquals(1, png.blackCount())
        assertContentEquals(EscPos.job(image, false), folder.resolve(files[0]).readBytes())
    }
}
