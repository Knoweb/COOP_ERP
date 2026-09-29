package lk.coopfed.knoweb.till.peripherals.jvm

import java.awt.image.BufferedImage
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lk.coopfed.knoweb.till.core.receipt.MonoBitmap
import lk.coopfed.knoweb.till.peripherals.EscPos
import lk.coopfed.knoweb.till.peripherals.PrintJob
import lk.coopfed.knoweb.till.peripherals.PrintResult
import lk.coopfed.knoweb.till.peripherals.PrinterPort

/** An ESC/POS printer on the shop's network, raw on TCP port 9100 (research report section 5.1). */
class NetworkPrinter(
    private val host: String,
    private val port: Int = 9100,
    private val timeoutMillis: Int = 5000,
) : PrinterPort {
    override val description = "network printer $host:$port"

    override suspend fun print(job: PrintJob): PrintResult = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            socket.soTimeout = timeoutMillis
            socket.getOutputStream().apply {
                write(EscPos.job(job.image, job.kickDrawer))
                flush()
            }
        }
        PrintResult("$host:$port")
    }
}

/**
 * No printer attached: every slip is written to [folder] as a PNG (what the paper would show) and
 * the .bin the printer would have received, byte for byte. The trial's default, and the way to
 * check a layout without paper.
 */
class PreviewFolderPrinter(private val folder: Path) : PrinterPort {
    override val description = "preview folder $folder"

    override suspend fun print(job: PrintJob): PrintResult = withContext(Dispatchers.IO) {
        Files.createDirectories(folder)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val base = "$stamp-${job.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}"
        val png = folder.resolve("$base.png").toFile()
        ImageIO.write(Png.of(job.image), "png", png)
        Files.write(folder.resolve("$base.bin"), EscPos.job(job.image, job.kickDrawer))
        PrintResult(png.absolutePath)
    }
}

/** The 1-bit image as a black-on-white PNG. */
object Png {
    fun of(image: MonoBitmap): BufferedImage {
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_BINARY)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                out.setRGB(x, y, if (image[x, y]) 0x000000 else 0xFFFFFF)
            }
        }
        return out
    }

    fun write(image: MonoBitmap, file: File) {
        file.parentFile?.mkdirs()
        ImageIO.write(of(image), "png", file)
    }

    /** Reads a PNG written by [write] back into a 1-bit image (for golden-image tests). */
    fun read(file: File): MonoBitmap {
        val img = ImageIO.read(file)
        val out = MonoBitmap(img.width, img.height)
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                out[x, y] = (img.getRGB(x, y) and 0xFFFFFF) == 0
            }
        }
        return out
    }
}
