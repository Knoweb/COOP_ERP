package lk.coopfed.knoweb.till.core

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import lk.coopfed.knoweb.till.core.id.IdGenerator
import lk.coopfed.knoweb.till.core.model.DeviceIdentity
import lk.coopfed.knoweb.till.core.model.IssuedReceipt
import lk.coopfed.knoweb.till.core.model.OutboxEntry
import lk.coopfed.knoweb.till.core.model.ReceiptLine
import lk.coopfed.knoweb.till.core.model.SessionRecord
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.port.Central
import lk.coopfed.knoweb.till.core.port.CentralRefused
import lk.coopfed.knoweb.till.core.port.CentralUnreachable
import lk.coopfed.knoweb.till.core.port.PinVerifier
import lk.coopfed.knoweb.till.core.port.Settings
import lk.coopfed.knoweb.till.core.port.TillClock
import lk.coopfed.knoweb.till.core.port.TillStore
import lk.coopfed.knoweb.till.core.port.UploadAnswer
import lk.coopfed.knoweb.till.core.sale.Basket
import lk.coopfed.knoweb.till.core.sale.ContentHash
import lk.coopfed.knoweb.till.core.sale.Facts
import lk.coopfed.knoweb.till.core.sale.TenderTooSmall
import lk.coopfed.knoweb.till.core.session.SessionRules
import lk.coopfed.knoweb.till.core.session.ZReport
import lk.coopfed.knoweb.till.core.snapshot.Catalogue
import lk.coopfed.knoweb.till.core.snapshot.Language
import lk.coopfed.knoweb.till.core.snapshot.Operator
import lk.coopfed.knoweb.till.core.snapshot.SnapshotDelta
import lk.coopfed.knoweb.till.core.snapshot.SnapshotState
import lk.coopfed.knoweb.till.core.snapshot.SnapshotVerifier
import lk.coopfed.knoweb.till.core.time.Times

/** The till cannot do this now; [message] says why in words for the cashier. */
class TillRefusal(message: String) : Exception(message)

/** What the status bar shows: online or not, what waits for central, the snapshot in use. */
data class TillStatus(
    val online: Boolean? = null,
    val pendingFacts: Long = 0,
    val snapshotVersion: Long = 0,
    val lastSyncAt: Instant? = null,
    val message: String? = null,
)

/**
 * The till's use cases (doc 26, 26A; doc 32): enrol, take the snapshot, sign in, open a session,
 * sell for cash, close the session with its Z-report, and upload the facts when central can be
 * reached. It writes every fact locally first, in one transaction with the counters that number
 * it, so the till sells the same with or without a network.
 *
 * Shared by every platform; the platform hands it its ports.
 */
class TillService(
    private val store: TillStore,
    private val central: Central,
    private val verifier: SnapshotVerifier,
    private val pins: PinVerifier,
    private val clock: TillClock,
    private val ids: IdGenerator,
    private val appVersion: String,
    private val localPrices: Map<String, Money> = emptyMap(),
) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val _status = MutableStateFlow(TillStatus())
    val status: StateFlow<TillStatus> = _status.asStateFlow()

    var device: DeviceIdentity? = null
        private set
    var catalogue: Catalogue = Catalogue(SnapshotState.EMPTY, localPrices)
        private set
    var operator: Operator? = null
        private set
    private var snapshotVersion = 0L
    private var failedPins = 0
    private var lockedUntil: Instant? = null

    /** Reads what the till already knows from its database. Call once at start. */
    suspend fun start() = lock.withLock {
        device = store.setting(Settings.DEVICE)?.let { json.decodeFromString(DeviceIdentity.serializer(), it) }
        device?.let { central.identify(it) }
        val snapshot = store.loadSnapshot()
        snapshotVersion = snapshot.version
        catalogue = Catalogue(snapshot, localPrices)
        publishStatus()
    }

    val isEnrolled: Boolean get() = device != null
    val shopLanguage: Language get() = catalogue.shop?.language ?: Language.EN

    // ================================================================= enrolment (doc 32 section 2)

    /**
     * Enrols with the one-time code from the back office. The answer gives the till its identity,
     * its credential, central's signing key, its next device sequence and its receipt series; the
     * till goes on from the higher of its own counters and central's, so re-enrolling never reuses
     * a number.
     */
    suspend fun enrol(serverUrl: String, deviceId: String, code: String, hardwareSerial: String): DeviceIdentity {
        val answer = central.enrol(serverUrl.trimEnd('/'), deviceId.trim(), code.trim(), hardwareSerial.trim(), appVersion)
        val rct = answer["series"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it.str("doc_type_code") == "RCT" }
            ?: throw TillRefusal("The enrolment answer carries no receipt (RCT) series for this till position")
        val credential = answer.getValue("credential").jsonObject
        val key = answer.getValue("signing_key").jsonObject
        val identity = DeviceIdentity(
            serverUrl = serverUrl.trimEnd('/'),
            deviceId = answer.str("device_id") ?: deviceId,
            hardwareSerial = hardwareSerial,
            ownerEntityId = answer.str("owner_entity_id")!!,
            locationId = answer.str("location_id")!!,
            tillPositionId = answer.str("till_position_id")!!,
            positionNo = answer["position_no"]?.jsonPrimitive?.intOrNull,
            primaryTill = answer["primary_till"]?.jsonPrimitive?.contentOrNull == "true",
            clientId = credential.str("client_id")!!,
            clientSecret = credential.str("client_secret")!!,
            tokenEndpoint = credential.str("token_endpoint")!!,
            signingKeyId = key.str("key_id")!!,
            signingPublicKey = key.str("public_key")!!,
            receiptSeriesId = rct.str("series_id")!!,
            receiptPrefix = rct.str("prefix")!!,
        )
        lock.withLock {
            store.transaction {
                store.putSetting(Settings.DEVICE, json.encodeToString(DeviceIdentity.serializer(), identity))
                val seq = answer.getValue("next_device_seq").jsonPrimitive.long
                store.putSetting(Settings.NEXT_DEVICE_SEQ, maxOf(seq, counter(Settings.NEXT_DEVICE_SEQ)).toString())
                val number = rct.getValue("next_number").jsonPrimitive.long
                store.putSetting(Settings.NEXT_RECEIPT_NUMBER, maxOf(number, counter(Settings.NEXT_RECEIPT_NUMBER)).toString())
            }
            device = identity
            central.identify(identity)
            publishStatus()
        }
        return identity
    }

    // ================================================================= the snapshot (doc 32 section 5)

    /**
     * Downloads the snapshot since the version the till holds, verifies it (signature, manifest,
     * table hashes) and applies it whole. A snapshot that fails verification is refused and the
     * till keeps selling with the one it has.
     *
     * @return the version in use afterwards
     */
    suspend fun refreshSnapshot(): Long {
        val identity = requireDevice()
        val answer = central.snapshot(snapshotVersion)
        val delta = SnapshotDelta.fromJson(answer)
        verifier.verify(delta, identity.locationId, identity.signingPublicKey)
        lock.withLock {
            val current = store.loadSnapshot()
            if (delta.version == current.version && !delta.fullSnapshotRequired) return current.version
            val next = current.apply(delta, businessDate())
            store.transaction { store.saveSnapshot(next) }
            snapshotVersion = next.version
            catalogue = Catalogue(next, localPrices)
            publishStatus()
            return next.version
        }
    }

    // ================================================================= signing in (26A section 8)

    /**
     * Signs [candidate] in with their PIN, checked against the Argon2id hash of the signed snapshot.
     * Five wrong PINs lock the till for fifteen minutes (26A section 8).
     */
    fun signIn(candidate: Operator, pin: String) {
        val now = clock.now()
        lockedUntil?.let { if (now < it) throw TillRefusal("Too many wrong PINs; try again after ${Times.printed(it, clock.zone)}") }
        val hash = candidate.pinHash ?: throw TillRefusal("${candidate.displayName} has no PIN set")
        if (!pins.verify(pin, hash)) {
            failedPins++
            if (failedPins >= 5) {
                lockedUntil = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 15 * 60 * 1000)
                failedPins = 0
            }
            throw TillRefusal("Wrong PIN")
        }
        failedPins = 0
        operator = candidate
    }

    /**
     * The trial's stand-in when the snapshot carries no operator with a PIN (the demo stack has no
     * till users yet): a cashier with a fixed id per till, as the backend's till simulator does.
     * Recorded as a deviation; a pilot till never offers it.
     */
    suspend fun signInTrialCashier(): Operator = lock.withLock {
        val id = store.setting("trial_operator_id") ?: ids.next().also { id ->
            store.transaction { store.putSetting("trial_operator_id", id) }
        }
        Operator(id, "Trial cashier", shopLanguage, null, emptyList()).also { operator = it }
    }

    fun signOut() {
        operator = null
    }

    // ================================================================= the session (doc 26 section 3.5)

    suspend fun openSession(floatAmount: Money): SessionRecord = lock.withLock {
        val identity = requireDevice()
        val cashier = operator ?: throw TillRefusal("Sign in first")
        if (store.openSession() != null) throw TillRefusal("A session is already open at this till")
        if (floatAmount.cents < 0) throw TillRefusal("The float cannot be negative")
        val now = now()
        val session = SessionRecord(
            sessionId = ids.next(),
            operatorUserId = cashier.userId,
            operatorName = cashier.displayName,
            businessDate = openBusinessDay(now),
            openedAt = Times.wholeSecond(now).epochSeconds,
            floatAmount = floatAmount,
        )
        store.transaction {
            store.saveSession(session)
            record("till_session.opened.v1", now, cashier.userId, Facts.sessionOpened(identity, session))
        }
        publishStatus()
        session
    }

    suspend fun currentSession(): SessionRecord? = lock.withLock { store.openSession() }

    /**
     * Sells the basket for cash: numbers the receipt from the till position's series, writes it and
     * its bundle to the outbox in one transaction, and returns it for printing. The basket is
     * emptied only when the receipt is safely written.
     */
    suspend fun sellForCash(basket: Basket, tendered: Money): IssuedReceipt = lock.withLock {
        val identity = requireDevice()
        val cashier = operator ?: throw TillRefusal("Sign in first")
        val session = store.openSession() ?: throw TillRefusal("Open a session first")
        if (basket.isEmpty) throw TillRefusal("The basket is empty")
        val gross = basket.total
        if (tendered < gross) throw TenderTooSmall(gross, tendered)
        val now = now()
        val language = shopLanguage
        val receipt = store.transaction {
            val number = next(Settings.NEXT_RECEIPT_NUMBER)
            val seq = next(Settings.NEXT_DEVICE_SEQ)
            val display = "${identity.receiptPrefix}-$number"
            val documentId = ids.next()
            val lines = basket.lines.mapIndexed { i, l ->
                ReceiptLine(
                    lineNo = i + 1,
                    skuId = l.item.skuId,
                    nameEn = l.item.name.en,
                    nameLocal = l.item.name.get(language),
                    uom = l.item.baseUom,
                    qty = l.qty,
                    unitPrice = l.unitPrice,
                    lineTotal = l.total,
                )
            }
            val document = Facts.receiptDocument(
                identity, documentId, number, display, now, session.businessDate, cashier.userId, gross, seq,
            )
            val lineJson = Facts.receiptLines(lines)
            val hash = ContentHash.of(document, lineJson)
            val receipt = IssuedReceipt(
                documentId = documentId,
                sessionId = session.sessionId,
                number = number,
                numberDisplay = display,
                issuedAt = Times.wholeSecond(now).epochSeconds,
                businessDate = session.businessDate,
                operatorUserId = cashier.userId,
                operatorName = cashier.displayName,
                lines = lines,
                gross = gross,
                tendered = tendered,
                change = tendered - gross,
                contentHash = hash,
                deviceSeq = seq,
            )
            store.saveReceipt(receipt)
            store.appendOutbox(
                Facts.event(
                    eventId = ids.next(), eventType = "receipt.issued.v1", deviceSeq = seq, occurredAt = now,
                    zone = clock.zone, actorUserId = cashier.userId,
                    payload = Facts.receiptPayload(document, lineJson, receipt),
                    aggregateType = "receipt", aggregateId = documentId, contentHash = hash,
                ),
            )
            receipt
        }
        basket.clear()
        publishStatus()
        receipt
    }

    /** The cashier's blind count closes the session; the till works out expected cash and the variance. */
    suspend fun closeSession(counted: Money): ZReport = lock.withLock {
        val identity = requireDevice()
        val session = store.openSession() ?: throw TillRefusal("No session is open")
        if (counted.cents < 0) throw TillRefusal("The count cannot be negative")
        val receipts = store.receiptsOfSession(session.sessionId)
        val open = ZReport.of(session, receipts)
        val now = now()
        val closed = session.copy(
            closedAt = Times.wholeSecond(now).epochSeconds,
            countedCash = counted,
            expectedCash = SessionRules.expectedCash(session, open.cashSales),
        )
        store.transaction {
            store.saveSession(closed)
            record("till_session.closed.v1", now, operator?.userId ?: session.operatorUserId, Facts.sessionClosed(identity, closed))
        }
        publishStatus()
        ZReport.of(closed, receipts)
    }

    suspend fun zReport(sessionId: String): ZReport? = lock.withLock {
        store.session(sessionId)?.let { ZReport.of(it, store.receiptsOfSession(it.sessionId)) }
    }

    // ================================================================= upload (doc 32 section 3)

    /**
     * Uploads what waits in the outbox, batch after batch, until it is empty, central says to
     * wait, or central cannot be reached. Then checks for a newer snapshot. Never throws for a
     * network failure: the till is simply offline.
     *
     * @return how many facts central acknowledged
     */
    suspend fun syncOnce(batchSize: Int = 50): Int {
        val identity = device ?: return 0
        var acknowledged = 0
        try {
            while (true) {
                val batch = lock.withLock { nextBatch(identity, batchSize) } ?: break
                val answer = central.upload(batch.json)
                when (answer) {
                    is UploadAnswer.Acknowledged -> lock.withLock {
                        store.transaction {
                            store.acknowledgeUpTo(answer.lastAppliedSeq)
                            store.putSetting(Settings.LAST_ACKNOWLEDGED, answer.lastAppliedSeq.toString())
                            answer.clockOffsetMs?.let { store.putSetting(Settings.CLOCK_OFFSET_MS, it.toString()) }
                            store.putSetting(Settings.LAST_SYNC_AT, clock.now().epochSeconds.toString())
                        }
                        acknowledged += (answer.lastAppliedSeq - batch.first + 1).coerceIn(0, batch.count.toLong()).toInt()
                    }
                    is UploadAnswer.RateLimited -> Unit
                }
                if (answer is UploadAnswer.RateLimited) {
                    lock.withLock { publishStatus(online = true, message = "Central is busy; the till tries again in ${answer.retryAfterSeconds} s") }
                    return acknowledged
                }
                answer as UploadAnswer.Acknowledged
                if (answer.lastAppliedSeq < batch.first) {
                    // Central took none of it (it waits for an earlier fact): try again on the next round.
                    lock.withLock { publishStatus(online = true, message = "Central has not applied the batch from ${batch.first} yet") }
                    return acknowledged
                }
                if (answer.lastAppliedSeq < batch.last) {
                    // Central applied only part of the batch; the rest goes again in a new one.
                    lock.withLock { store.transaction { store.putSetting(Settings.BATCH_IN_FLIGHT, "") } }
                }
            }
            val latest = runCatching { heartbeat(identity) }.getOrNull()
            if (latest != null && latest > snapshotVersion) refreshSnapshot()
            lock.withLock { publishStatus(online = true, message = null) }
        } catch (e: CentralUnreachable) {
            lock.withLock { publishStatus(online = false, message = "Offline: selling continues; ${e.message}") }
        } catch (e: CentralRefused) {
            lock.withLock { publishStatus(online = true, message = "Central refused: ${e.problem.take(160)}") }
        }
        return acknowledged
    }

    private class Batch(val count: Int, val json: JsonObject, val first: Long, val last: Long)

    /** The oldest pending facts as one batch, or null when nothing waits. */
    private fun nextBatch(@Suppress("UNUSED_PARAMETER") identity: DeviceIdentity, batchSize: Int): Batch? {
        val pending = store.pendingOutbox(batchSize)
        if (pending.isEmpty()) return null
        val first = pending.first().deviceSeq
        val last = pending.last().deviceSeq
        val inFlight = store.setting(Settings.BATCH_IN_FLIGHT)?.split('|')
        val batchId = if (inFlight != null && inFlight.size == 3 && inFlight[1] == "$first" && inFlight[2] == "$last") {
            inFlight[0]
        } else {
            ids.next().also { store.transaction { store.putSetting(Settings.BATCH_IN_FLIGHT, "$it|$first|$last") } }
        }
        val batch = buildJsonObject {
            put("batch_id", batchId)
            put("first_seq", first)
            put("last_seq", last)
            put("app_version", appVersion)
            put("snapshot_version_in_use", snapshotVersion)
            put("device_clock", Times.iso(clock.now()))
            put("events", JsonArray(pending.map { json.parseToJsonElement(it.json) }))
        }
        return Batch(pending.size, batch, first, last)
    }

    private suspend fun heartbeat(identity: DeviceIdentity): Long? {
        val report = lock.withLock {
            buildJsonObject {
                put("app_version", appVersion)
                put("snapshot_version", snapshotVersion)
                put("pending_event_count", store.pendingCount())
                put("last_acknowledged_seq", counterOr(Settings.LAST_ACKNOWLEDGED, 0))
                put("device_clock", Times.iso(clock.now()))
            }
        }
        return central.heartbeat(report)["snapshot_version"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    }

    // ================================================================= helpers

    private fun requireDevice(): DeviceIdentity = device ?: throw TillRefusal("Enrol this till first")

    /** The till's clock corrected by the offset central last reported (TillEvent.occurred_at). */
    private fun now(): Instant {
        val offset = store.setting(Settings.CLOCK_OFFSET_MS)?.toLongOrNull() ?: 0
        return clock.now() + offset.milliseconds
    }

    /**
     * The business date a new session opens on: today's date at the shop. When it moves on, rows
     * held for the new day go live (doc 32 section 5.2).
     */
    private fun openBusinessDay(now: Instant): LocalDate {
        val today = Times.date(now, clock.zone)
        val current = store.setting(Settings.BUSINESS_DATE)?.let { LocalDate.parse(it) }
        if (current == null || today > current) {
            store.transaction {
                store.putSetting(Settings.BUSINESS_DATE, today.toString())
                val snapshot = store.loadSnapshot()
                if (snapshot.held.isNotEmpty()) {
                    val next = snapshot.dayOpen(today)
                    store.saveSnapshot(next)
                    catalogue = Catalogue(next, localPrices)
                }
            }
            return today
        }
        return current
    }

    private fun businessDate(): LocalDate =
        store.setting(Settings.BUSINESS_DATE)?.let { LocalDate.parse(it) } ?: Times.date(clock.now(), clock.zone)

    /** Appends a fact with the next device sequence number. Call inside a transaction. */
    private fun record(eventType: String, at: Instant, actor: String?, payload: JsonObject): OutboxEntry {
        val entry = Facts.event(ids.next(), eventType, next(Settings.NEXT_DEVICE_SEQ), at, clock.zone, actor, payload)
        store.appendOutbox(entry)
        return entry
    }

    /** Takes the counter's value and moves it on by one. Call inside a transaction. */
    private fun next(key: String): Long {
        val value = counter(key)
        store.putSetting(key, (value + 1).toString())
        return value
    }

    private fun counter(key: String): Long = counterOr(key, 1)

    private fun counterOr(key: String, default: Long): Long = store.setting(key)?.toLongOrNull() ?: default

    private fun publishStatus(online: Boolean? = _status.value.online, message: String? = _status.value.message) {
        _status.value = TillStatus(
            online = online,
            pendingFacts = store.pendingCount(),
            snapshotVersion = snapshotVersion,
            lastSyncAt = store.setting(Settings.LAST_SYNC_AT)?.toLongOrNull()?.let { Instant.fromEpochSeconds(it) },
            message = message,
        )
    }

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
}
