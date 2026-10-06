package lk.coopfed.knoweb.till.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Instant
import lk.coopfed.knoweb.till.core.model.Anomaly
import lk.coopfed.knoweb.till.core.snapshot.Item
import lk.coopfed.knoweb.till.core.snapshot.Operator
import lk.coopfed.knoweb.till.core.time.Times

/**
 * The till, one screen at a time (research report 7A.2): enrol, sign in, open the session, sell,
 * close with the blind count, the Z-report. Every action has a key on a PC: Enter adds the scan,
 * F2 takes cash, F10 closes the session, Esc cancels a dialog. The scan field keeps the focus, so a
 * keyboard-mode scanner always types into it.
 */
@Composable
fun TillApp(controller: TillController, defaults: EnrolDefaults = EnrolDefaults()) {
    LaunchedEffect(Unit) { controller.start() }
    TillTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TopBar(controller)
                Banners(controller)
                MessageBar(controller)
                controller.problemsShown?.let { ProblemsDialog(controller, it) }
                Box(Modifier.fillMaxSize().padding(16.dp)) {
                    when (controller.screen) {
                        Screen.STARTING -> Text("Opening the till's database…")
                        Screen.ENROL -> EnrolScreen(controller, defaults)
                        Screen.SIGN_IN -> SignInScreen(controller)
                        Screen.OPEN_SESSION -> OpenSessionScreen(controller)
                        Screen.SELL -> SellScreen(controller)
                        Screen.CLOSE_SESSION -> CloseSessionScreen(controller)
                        Screen.Z_REPORT -> ZReportScreen(controller)
                    }
                }
            }
        }
    }
}

/** What the enrol form is filled with: the local stack's addresses and the trial till's serial. */
data class EnrolDefaults(
    val serverUrl: String = "http://localhost:8080",
    val deviceId: String = "",
    val code: String = "",
    val hardwareSerial: String = "DESKTOP-TRIAL-S01",
)

@Composable
private fun TopBar(c: TillController) {
    val s = c.status
    Row(
        Modifier.fillMaxWidth().background(TillColors.accent).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.shopName, color = TillColors.onAccent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(listOfNotNull(c.tillLabel, c.operatorName).joinToString(" · "), color = TillColors.onAccent, fontSize = 13.sp)
        }
        val (label, fg, bg) = when (s.online) {
            true -> Triple("Online", TillColors.issuedText, TillColors.issuedBg)
            false -> Triple("Offline", TillColors.disputedText, TillColors.disputedBg)
            null -> Triple("Not synced yet", TillColors.text, TillColors.surfaceSubtle)
        }
        Text(
            "$label · ${s.pendingFacts} to upload · snapshot v${s.snapshotVersion}",
            color = fg, modifier = Modifier.background(bg).padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 13.sp,
        )
        if (s.problems > 0) {
            // Facts central refused and other problems the office must see (TWK-05).
            OutlinedButton(onClick = { c.showProblems() }) {
                Text("${s.problems} problem${if (s.problems == 1L) "" else "s"} for the office", color = TillColors.onAccent)
            }
        }
        if (c.screen != Screen.ENROL && c.screen != Screen.STARTING) {
            OutlinedButton(onClick = { c.syncNow() }) { Text("Sync now", color = TillColors.onAccent) }
        }
        if (c.operatorName != null && c.screen != Screen.SIGN_IN) {
            OutlinedButton(onClick = { c.signOut() }) { Text("Sign out", color = TillColors.onAccent) }
        }
    }
}

/** What a supervisor must see on every screen: a revoke, the version floor, a stale snapshot, uploading stopped. */
@Composable
private fun Banners(c: TillController) {
    for (banner in c.status.banners) {
        Text(
            banner,
            color = TillColors.alertText,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth().background(TillColors.alertBg).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ProblemsDialog(c: TillController, problems: List<Anomaly>) {
    AlertDialog(
        onDismissRequest = { c.closeProblems() },
        title = { Text("Problems for the office") },
        text = {
            LazyColumn(Modifier.widthIn(max = 640.dp)) {
                itemsIndexed(problems) { _, p ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(
                            Times.printed(Instant.fromEpochMilliseconds(p.notedAt), c.zoneForScreens) + " · " + p.kind,
                            color = TillColors.textMuted, fontSize = 12.sp,
                        )
                        Text(p.detail)
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { Button(onClick = { c.closeProblems() }) { Text("Seen") } },
    )
}

@Composable
private fun MessageBar(c: TillController) {
    val text = c.message ?: c.status.message ?: return
    val error = c.message != null && c.messageIsError
    Text(
        text,
        color = if (error) TillColors.alertText else TillColors.text,
        modifier = Modifier.fillMaxWidth().background(if (error) TillColors.alertBg else TillColors.surface).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Surface(Modifier.widthIn(max = 560.dp), tonalElevation = 1.dp, shadowElevation = 2.dp) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            content()
        }
    }
}

@Composable
private fun EnrolScreen(c: TillController, d: EnrolDefaults) {
    var server by remember { mutableStateOf(d.serverUrl) }
    var device by remember { mutableStateOf(d.deviceId) }
    var code by remember { mutableStateOf(d.code) }
    var serial by remember { mutableStateOf(d.hardwareSerial) }
    Panel("Enrol this till") {
        Text("Type the device id and the one-time code the back office issued for this till.", color = TillColors.textMuted)
        OutlinedTextField(server, { server = it }, label = { Text("Central (server address)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(device, { device = it }, label = { Text("Device id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(code, { code = it }, label = { Text("One-time code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(serial, { serial = it }, label = { Text("Hardware serial") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { c.enrol(server, device, code, serial) }, enabled = !c.busy && device.isNotBlank() && code.isNotBlank()) {
            Text(if (c.busy) "Enrolling…" else "Enrol and take the snapshot")
        }
    }
}

@Composable
private fun SignInScreen(c: TillController) {
    var chosen by remember { mutableStateOf<Operator?>(null) }
    var pin by remember { mutableStateOf("") }
    Panel("Sign in") {
        if (c.operators.isEmpty() && c.trialCashierOffered) {
            Text(
                "The snapshot has no operator with a PIN for this shop yet. This PC is set up for the trial: continue as the till's trial cashier.",
                color = TillColors.textMuted,
            )
            Button(onClick = { c.signInTrialCashier() }, enabled = !c.busy) { Text("Continue as trial cashier") }
        } else if (c.operators.isEmpty()) {
            // TWK-04: no stand-in on a till that did not opt in, or once the shop has had operators.
            Text(
                "This till has no operator for this shop. The office must assign an operator with a PIN to this shop; " +
                    "the till receives them with its next snapshot.",
                color = TillColors.textMuted,
            )
        } else {
            c.operators.forEach { op ->
                Text(
                    op.displayName,
                    modifier = Modifier.fillMaxWidth().clickable { chosen = op; pin = "" }
                        .background(if (chosen == op) TillColors.issuedBg else TillColors.surface).padding(10.dp),
                )
            }
            chosen?.let { op ->
                OutlinedTextField(
                    pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text("PIN for ${op.displayName}") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { c.signIn(op, pin) }),
                )
                Button(onClick = { c.signIn(op, pin) }, enabled = !c.busy && pin.length >= 4) { Text(if (c.busy) "Checking…" else "Sign in") }
            }
        }
    }
}

@Composable
private fun OpenSessionScreen(c: TillController) {
    var float by remember { mutableStateOf("2000.00") }
    var correcting by remember { mutableStateOf(false) }
    Panel("Open the session") {
        Text("Count the float into the drawer and key it.", color = TillColors.textMuted)
        OutlinedTextField(
            float, { float = it }, label = { Text("Float (LKR)") }, singleLine = true,
            keyboardActions = KeyboardActions(onDone = { c.openSession(float) }),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        )
        Button(onClick = { c.openSession(float) }, enabled = !c.busy) { Text("Open session") }
        val date = c.businessDate
        if (date != null) {
            Text("The till's business date: $date", color = TillColors.textMuted)
            // A date opened from a wrong PC clock is corrected by a supervisor (decision D-4).
            if (c.clockDate?.let { it < date } == true) {
                OutlinedButton(onClick = { correcting = true }) { Text("Move the business date back (supervisor)") }
            }
        }
    }
    if (correcting) BusinessDateDialog(c) { correcting = false }
}

@Composable
private fun BusinessDateDialog(c: TillController, close: () -> Unit) {
    var date by remember { mutableStateOf(c.clockDate?.toString() ?: "") }
    var supervisor by remember { mutableStateOf(c.supervisors.firstOrNull()) }
    var pin by remember { mutableStateOf("") }
    val trial = c.supervisors.isEmpty() && c.trialCashierOffered
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Move the business date back") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Receipts and sessions already issued keep their date. Check the PC's date and time first.",
                    color = TillColors.textMuted,
                )
                OutlinedTextField(date, { date = it }, label = { Text("Business date (yyyy-mm-dd)") }, singleLine = true)
                if (!trial) {
                    if (c.supervisors.isEmpty()) {
                        Text("No operator of this shop may correct the business date; ask the office.", color = TillColors.alertText)
                    }
                    c.supervisors.forEach { op ->
                        Text(
                            op.displayName,
                            modifier = Modifier.fillMaxWidth().clickable { supervisor = op }
                                .background(if (supervisor == op) TillColors.issuedBg else TillColors.surface).padding(8.dp),
                        )
                    }
                    OutlinedTextField(
                        pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text("Supervisor's PIN") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { c.correctBusinessDate(date, if (trial) null else supervisor, pin, close) },
                enabled = !c.busy && (trial || (supervisor != null && pin.length >= 4)),
            ) { Text("Correct") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

@Composable
private fun SellScreen(c: TillController) {
    var scan by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Item>>(emptyList()) }
    var paying by remember { mutableStateOf(false) }
    val scanFocus = remember { FocusRequester() }
    LaunchedEffect(paying, c.needsPrice) { if (!paying && c.needsPrice == null) scanFocus.requestFocus() }

    Row(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.F2 -> { if (c.lines.isNotEmpty()) paying = true; true }
                Key.F10 -> { c.toCloseSession(); true }
                else -> false
            }
        },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(3f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                scan,
                { scan = it; results = if (it.length >= 2 && !it.all(Char::isDigit)) c.search(it) else emptyList() },
                label = { Text("Scan or type a barcode or name, then Enter") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(scanFocus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    results = if (results.size == 1) { c.add(results.single()); emptyList() } else c.scan(scan)
                    scan = ""
                }),
            )
            if (results.isNotEmpty()) {
                Surface(tonalElevation = 2.dp) {
                    Column {
                        results.take(8).forEach { item ->
                            Row(
                                Modifier.fillMaxWidth().clickable { c.add(item); results = emptyList(); scan = "" }.padding(8.dp),
                            ) {
                                Text("${item.name.en}  ${item.name.si ?: ""}", Modifier.weight(1f))
                                Text(item.price?.display() ?: "no price", color = TillColors.textMuted)
                            }
                        }
                    }
                }
            }
            Basket(c)
        }
        Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
                Column(Modifier.padding(16.dp)) {
                    Text("Total", color = TillColors.textMuted)
                    Text("LKR ${c.total.display()}", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text("${c.lines.size} lines", color = TillColors.textMuted)
                }
            }
            Button(onClick = { paying = true }, enabled = c.lines.isNotEmpty() && !c.busy, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text("Cash  (F2)", fontSize = 20.sp)
            }
            OutlinedButton(onClick = { c.clearSale() }, enabled = c.lines.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Clear the sale") }
            OutlinedButton(onClick = { c.reprintLast() }, enabled = c.lastReceipt != null, modifier = Modifier.fillMaxWidth()) {
                Text("Reprint ${c.lastReceipt?.numberDisplay ?: "last receipt"}")
            }
            OutlinedButton(onClick = { c.toCloseSession() }, modifier = Modifier.fillMaxWidth()) { Text("Close the session  (F10)") }
            c.lastReceipt?.let { r ->
                Text("Last: ${r.numberDisplay} · LKR ${r.gross.display()} · change ${r.change.display()}", color = TillColors.textMuted)
            }
            Text("Printer: ${c.printerDescription}", color = TillColors.textMuted, fontSize = 12.sp)
        }
    }
    if (paying) CashDialog(c) { paying = false }
    c.needsPrice?.let { PriceDialog(c, it) }
}

@Composable
private fun Basket(c: TillController) {
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(c.lines) { i, line ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(line.item.name.en, fontWeight = FontWeight.Medium)
                        line.item.name.si?.let { Text(it, color = TillColors.textMuted, fontSize = 13.sp) }
                    }
                    TextButton(onClick = { c.changeQty(i, -1) }) { Text("−") }
                    Text(line.qty.display(), Modifier.width(40.dp), textAlign = TextAlign.Center)
                    TextButton(onClick = { c.changeQty(i, +1) }) { Text("+") }
                    Text("× ${line.unitPrice.display()}", Modifier.width(110.dp), textAlign = TextAlign.End, color = TillColors.textMuted)
                    Text(line.total.display(), Modifier.width(120.dp), textAlign = TextAlign.End, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { c.removeLine(i) }) { Text("Remove") }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CashDialog(c: TillController, close: () -> Unit) {
    var given by remember { mutableStateOf(c.total.plain()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Cash: LKR ${c.total.display()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    given, { given = it }, label = { Text("Cash given (LKR)") }, singleLine = true,
                    modifier = Modifier.focusRequester(focus).onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { close(); true } else false
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { c.payCash(given, close) }),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("500", "1000", "5000").forEach { note -> OutlinedButton(onClick = { given = note }) { Text(note) } }
                }
            }
        },
        confirmButton = { Button(onClick = { c.payCash(given, close) }, enabled = !c.busy) { Text("Complete (Enter)") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel (Esc)") } },
    )
}

@Composable
private fun PriceDialog(c: TillController, item: Item) {
    var price by remember(item) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { c.cancelKeyedPrice() },
        title = { Text("Price of ${item.name.en}") },
        text = {
            Column {
                Text("The snapshot carries no price for this item yet; key the shelf price.", color = TillColors.textMuted)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    price, { price = it }, label = { Text("Unit price (LKR)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { c.addWithKeyedPrice(price) }),
                )
            }
        },
        confirmButton = { Button(onClick = { c.addWithKeyedPrice(price) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = { c.cancelKeyedPrice() }) { Text("Cancel") } },
    )
}

@Composable
private fun CloseSessionScreen(c: TillController) {
    var counted by remember { mutableStateOf("") }
    Panel("Close the session") {
        Text("Count the cash in the drawer and key the total. The till does not show what it expects.", color = TillColors.textMuted)
        OutlinedTextField(
            counted, { counted = it }, label = { Text("Counted cash (LKR)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { c.closeSession(counted) }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { c.closeSession(counted) }, enabled = !c.busy && counted.isNotBlank()) { Text("Close and print the Z-report") }
            OutlinedButton(onClick = { c.backToSelling() }) { Text("Back to selling") }
        }
    }
}

@Composable
private fun ZReportScreen(c: TillController) {
    val z = c.lastZReport ?: return
    Panel("Z-report · ${z.session.businessDate}") {
        @Composable
        fun line(label: String, value: String, bold: Boolean = false) = Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f))
            Text(value, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        }
        line("Cashier", z.session.operatorName)
        line("Receipts", "${z.receiptCount}" + (z.firstReceipt?.let { "  ($it … ${z.lastReceipt})" } ?: ""))
        line("Sales", "LKR ${z.grossSales.display()}", bold = true)
        line("Float", z.floatAmount.display())
        line("Expected cash", z.expectedCash.display(), bold = true)
        line("Counted cash", z.countedCash?.display() ?: "-")
        line("Variance", z.variance?.display() ?: "-", bold = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { c.afterZReport() }) { Text("Done") }
            OutlinedButton(onClick = { c.reprintZReport() }) { Text("Print again") }
        }
    }
}
