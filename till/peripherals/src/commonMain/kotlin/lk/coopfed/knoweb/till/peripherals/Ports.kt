package lk.coopfed.knoweb.till.peripherals

import lk.coopfed.knoweb.till.core.receipt.MonoBitmap

/** A slip ready to print: the rasterised image and whether the cash drawer opens with it. */
class PrintJob(val name: String, val image: MonoBitmap, val kickDrawer: Boolean)

/** Where a slip went, for the screen: a printer's address or a folder. */
data class PrintResult(val where: String)

/**
 * A receipt printer (26A section 7 PosPrinter): the till hands it a raster image; the transport
 * (TCP 9100, USB, serial, a vendor SDK, a preview folder) is the adapter's business.
 */
interface PrinterPort {
    val description: String
    suspend fun print(job: PrintJob): PrintResult
}

/** Records what would have printed; for tests and a till with no printer configured. */
class SimulatorPrinter : PrinterPort {
    private val jobs = mutableListOf<PrintJob>()
    override val description = "simulator"

    override suspend fun print(job: PrintJob): PrintResult {
        jobs += job
        return PrintResult("simulator")
    }

    fun printedJobs(): List<PrintJob> = jobs.toList()
}

interface CashDrawer {
    fun open()
}

class SimulatorCashDrawer : CashDrawer {
    var opened: Boolean = false
        private set

    override fun open() {
        opened = true
    }
}

interface CustomerDisplay {
    fun show(message: String)
}

class SimulatorCustomerDisplay : CustomerDisplay {
    var lastMessage: String? = null
        private set

    override fun show(message: String) {
        lastMessage = message
    }
}

/** A scale reading in grams (research report section 5.2; serial protocols come later). */
interface Scale {
    fun weightGrams(): Long
}

class SimulatorScale : Scale {
    override fun weightGrams(): Long = 1000
}
