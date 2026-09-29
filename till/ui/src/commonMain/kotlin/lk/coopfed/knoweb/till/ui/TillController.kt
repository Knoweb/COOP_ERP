package lk.coopfed.knoweb.till.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import lk.coopfed.knoweb.till.core.TillRefusal
import lk.coopfed.knoweb.till.core.TillService
import lk.coopfed.knoweb.till.core.TillStatus
import lk.coopfed.knoweb.till.core.model.IssuedReceipt
import lk.coopfed.knoweb.till.core.model.SessionRecord
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.port.CentralRefused
import lk.coopfed.knoweb.till.core.port.CentralUnreachable
import lk.coopfed.knoweb.till.core.receipt.MonoBitmap
import lk.coopfed.knoweb.till.core.receipt.ReceiptLayout
import lk.coopfed.knoweb.till.core.receipt.ReceiptLayouts
import lk.coopfed.knoweb.till.core.receipt.SlipHeader
import lk.coopfed.knoweb.till.core.sale.Basket
import lk.coopfed.knoweb.till.core.sale.BasketLine
import lk.coopfed.knoweb.till.core.sale.TenderTooSmall
import lk.coopfed.knoweb.till.core.session.ZReport
import lk.coopfed.knoweb.till.core.snapshot.Item
import lk.coopfed.knoweb.till.core.snapshot.Operator
import lk.coopfed.knoweb.till.core.snapshot.SnapshotRejected
import lk.coopfed.knoweb.till.peripherals.PrintJob
import lk.coopfed.knoweb.till.peripherals.PrinterPort
import lk.coopfed.knoweb.till.render.ReceiptRasteriser

/** Where the till is: the screen the cashier sees. */
enum class Screen { STARTING, ENROL, SIGN_IN, OPEN_SESSION, SELL, CLOSE_SESSION, Z_REPORT }

/**
 * Drives the shared screens from the [TillService]: holds what the screens show (Compose state),
 * runs each action off the UI thread, turns refusals into a message for the cashier, prints the
 * slips, and uploads in the background. No business rule lives here.
 */
class TillController(
    private val service: TillService,
    private val printer: PrinterPort,
    private val rasteriser: ReceiptRasteriser,
    private val scope: CoroutineScope,
    private val zone: TimeZone,
    private val paperDots: Int = MonoBitmap.DOTS_80MM,
    private val syncEveryMillis: Long = 20_000,
) {
    var screen by mutableStateOf(Screen.STARTING)
        private set
    var busy by mutableStateOf(false)
        private set
    /** The last thing to tell the cashier, good or bad. */
    var message by mutableStateOf<String?>(null)
        private set
    var messageIsError by mutableStateOf(false)
        private set
    var status by mutableStateOf(TillStatus())
        private set
    var session by mutableStateOf<SessionRecord?>(null)
        private set
    var lastReceipt by mutableStateOf<IssuedReceipt?>(null)
        private set
    var lastZReport by mutableStateOf<ZReport?>(null)
        private set
    /** An item scanned with no price in the snapshot or the price book: the cashier keys it. */
    var needsPrice by mutableStateOf<Item?>(null)
        private set

    private val basket = Basket()
    val lines = mutableStateListOf<BasketLine>()
    var total by mutableStateOf(Money.ZERO)
        private set

    val operators: List<Operator> get() = service.catalogue.operators
    val operatorName: String? get() = service.operator?.displayName
    val shopName: String get() = service.catalogue.shop?.name?.get(service.shopLanguage) ?: service.device?.let { "Shop ${it.locationId.takeLast(4)}" } ?: "COOP till"
    val tillLabel: String get() = service.device?.let { "${it.receiptPrefix} · ${it.hardwareSerial}" } ?: "not enrolled"
    val printerDescription: String get() = printer.description

    private var syncJob: Job? = null

    fun start() = act {
        service.start()
        session = service.currentSession()
        screen = if (service.isEnrolled) Screen.SIGN_IN else Screen.ENROL
        watchStatus()
        if (service.isEnrolled) startSyncLoop()
    }

    // ---- enrolment ----

    fun enrol(serverUrl: String, deviceId: String, code: String, serial: String) = act {
        service.enrol(serverUrl, deviceId, code, serial)
        val version = service.refreshSnapshot()
        say("Enrolled. Snapshot version $version: ${service.catalogue.items.size} items, ${operators.size} operators.")
        screen = Screen.SIGN_IN
        startSyncLoop()
    }

    // ---- signing in ----

    fun signIn(operator: Operator, pin: String) = act {
        withContext(Dispatchers.Default) { service.signIn(operator, pin) }
        afterSignIn()
    }

    fun signInTrialCashier() = act {
        service.signInTrialCashier()
        afterSignIn()
    }

    private suspend fun afterSignIn() {
        session = service.currentSession()
        screen = if (session == null) Screen.OPEN_SESSION else Screen.SELL
        say("Signed in as ${service.operator?.displayName}")
    }

    fun signOut() {
        service.signOut()
        screen = Screen.SIGN_IN
    }

    // ---- session ----

    fun openSession(float: String) = act {
        session = service.openSession(parseMoney(float))
        screen = Screen.SELL
        say("Session open with a float of LKR ${session!!.floatAmount.display()}")
        syncSoon()
    }

    fun toCloseSession() {
        if (lines.isNotEmpty()) return say("Finish or clear the sale first", error = true)
        screen = Screen.CLOSE_SESSION
    }

    fun backToSelling() {
        screen = Screen.SELL
    }

    fun closeSession(counted: String) = act {
        val z = service.closeSession(parseMoney(counted))
        lastZReport = z
        session = null
        screen = Screen.Z_REPORT
        say("Session closed. Variance LKR ${z.variance?.display()}")
        print("Z-${z.session.businessDate}", ReceiptLayouts.zReport(z, header(), service.shopLanguage, zone), kickDrawer = false)
        syncSoon()
    }

    fun afterZReport() {
        screen = Screen.OPEN_SESSION
    }

    fun reprintZReport() = act {
        lastZReport?.let { z -> print("Z-${z.session.businessDate}-reprint", ReceiptLayouts.zReport(z, header(), service.shopLanguage, zone), false) }
    }

    // ---- selling ----

    /** A code from the scan field: a barcode first, else a search the screen shows. */
    fun scan(code: String): List<Item> {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return emptyList()
        val item = service.catalogue.byBarcode(trimmed)
        if (item != null) {
            add(item)
            return emptyList()
        }
        val found = service.catalogue.search(trimmed)
        if (found.isEmpty()) say("No item with barcode or name \"$trimmed\" in the snapshot", error = true)
        return found
    }

    fun search(query: String): List<Item> = service.catalogue.search(query)

    fun add(item: Item) {
        val price = item.price
        if (price == null) {
            needsPrice = item
            return
        }
        basket.add(item, price)
        refreshBasket()
        message = null
    }

    fun addWithKeyedPrice(price: String) {
        val item = needsPrice ?: return
        try {
            basket.add(item, parseMoney(price))
            needsPrice = null
            refreshBasket()
        } catch (e: TillRefusal) {
            say(e.message ?: "Not a price", error = true)
        }
    }

    fun cancelKeyedPrice() {
        needsPrice = null
    }

    fun changeQty(index: Int, delta: Int) {
        val line = lines.getOrNull(index) ?: return
        val next = line.qty.milli + delta * 1000L
        if (next <= 0) basket.remove(index) else basket.setQty(index, lk.coopfed.knoweb.till.core.money.Qty(next))
        refreshBasket()
    }

    fun removeLine(index: Int) {
        basket.remove(index)
        refreshBasket()
    }

    fun clearSale() {
        basket.clear()
        refreshBasket()
    }

    /** Cash tendered; the receipt is numbered, stored, printed, and the drawer opens. */
    fun payCash(tendered: String, onDone: () -> Unit = {}) = act {
        val receipt = service.sellForCash(basket, parseMoney(tendered))
        refreshBasket()
        lastReceipt = receipt
        onDone()
        say("${receipt.numberDisplay}: change LKR ${receipt.change.display()}")
        print(receipt.numberDisplay, ReceiptLayouts.receipt(receipt, header(), service.shopLanguage, zone), kickDrawer = true)
        syncSoon()
    }

    fun reprintLast() = act {
        lastReceipt?.let { r -> print("${r.numberDisplay}-reprint", ReceiptLayouts.receipt(r, header(), service.shopLanguage, zone, reprint = true), false) }
    }

    // ---- sync ----

    fun syncNow() = act {
        val sent = service.syncOnce()
        if (service.status.value.online == true) say("Synced: $sent facts acknowledged, snapshot v${service.status.value.snapshotVersion}")
    }

    private fun startSyncLoop() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            while (isActive) {
                runCatching { service.syncOnce() }
                delay(syncEveryMillis)
            }
        }
    }

    private fun syncSoon() {
        scope.launch { runCatching { service.syncOnce() } }
    }

    private fun watchStatus() {
        scope.launch { service.status.collect { status = it } }
    }

    // ---- helpers ----

    private fun header() = SlipHeader(
        shopName = shopName,
        shopCode = service.catalogue.shop?.code ?: "",
        counter = service.device?.positionNo?.toString() ?: "-",
    )

    private suspend fun print(name: String, layout: ReceiptLayout, kickDrawer: Boolean) {
        try {
            val image = withContext(Dispatchers.Default) { rasteriser.rasterise(layout, paperDots) }
            val where = printer.print(PrintJob(name, image, kickDrawer))
            message = (message?.let { "$it · " } ?: "") + "printed to ${where.where}"
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // The sale is already a fact; a printer fault never undoes it.
            say("The sale is saved but printing failed: ${e.message}. Reprint when the printer is back.", error = true)
        }
    }

    private fun refreshBasket() {
        lines.clear()
        lines.addAll(basket.lines)
        total = basket.total
    }

    private fun parseMoney(text: String): Money = try {
        Money.parse(text.replace(",", ""))
    } catch (e: IllegalArgumentException) {
        throw TillRefusal("\"$text\" is not an amount")
    }

    private fun say(text: String, error: Boolean = false) {
        message = text
        messageIsError = error
    }

    /** Runs an action off the screen's thread; a refusal becomes the message, never a crash. */
    private fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: TillRefusal) {
                say(e.message ?: "Refused", error = true)
            } catch (e: TenderTooSmall) {
                say("Cash LKR ${e.tendered.display()} is less than the total LKR ${e.total.display()}", error = true)
            } catch (e: SnapshotRejected) {
                say("The snapshot was refused: ${e.message}. The till keeps the one it has.", error = true)
            } catch (e: CentralUnreachable) {
                say("Central cannot be reached: ${e.message}", error = true)
            } catch (e: CentralRefused) {
                say("Central refused (${e.status}): ${e.problem.take(200)}", error = true)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                say("Something went wrong: ${e.message ?: e::class.simpleName}", error = true)
            } finally {
                busy = false
            }
        }
    }
}
