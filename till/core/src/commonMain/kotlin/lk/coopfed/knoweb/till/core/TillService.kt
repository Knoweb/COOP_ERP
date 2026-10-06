package lk.coopfed.knoweb.till.core

import kotlin.math.abs
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import lk.coopfed.knoweb.till.core.id.IdGenerator
import lk.coopfed.knoweb.till.core.model.Anomaly
import lk.coopfed.knoweb.till.core.model.DeviceIdentity
import lk.coopfed.knoweb.till.core.model.IssuedReceipt
import lk.coopfed.knoweb.till.core.model.OutboxEntry
import lk.coopfed.knoweb.till.core.model.ReceiptLine
import lk.coopfed.knoweb.till.core.model.SessionRecord
import lk.coopfed.knoweb.till.core.money.Money
import lk.coopfed.knoweb.till.core.port.Central
import lk.coopfed.knoweb.till.core.port.CentralRefused
import lk.coopfed.knoweb.till.core.port.CentralUnreachable
import lk.coopfed.knoweb.till.core.port.EventOutcome
import lk.coopfed.knoweb.till.core.port.Instruction
import lk.coopfed.knoweb.till.core.port.PinVerifier
import lk.coopfed.knoweb.till.core.port.Settings
import lk.coopfed.knoweb.till.core.port.TillClock
import lk.coopfed.knoweb.till.core.port.TillStore
import lk.coopfed.knoweb.till.core.port.UnreadablePinHash
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
import lk.coopfed.knoweb.till.core.snapshot.SnapshotNotContinuous
import lk.coopfed.knoweb.till.core.snapshot.SnapshotState
import lk.coopfed.knoweb.till.core.snapshot.SnapshotVerifier
import lk.coopfed.knoweb.till.core.time.Times

/** The till cannot do this now; [message] says why in words for the cashier. */
class TillRefusal(message: String) : Exception(message)

/** What the status bar shows: online or not, what waits for central, the snapshot in use, what the office must know. */
data class TillStatus(
    val online: Boolean? = null,
    val pendingFacts: Long = 0,
    val snapshotVersion: Long = 0,
    val lastSyncAt: Instant? = null,
    val message: String? = null,
    /** Problems the office has not looked at yet (the local anomaly table). */
    val problems: Long = 0,
    /** What a supervisor must see: a revoke, the version floor, a stale snapshot, uploading stopped. */
    val banners: List<String> = emptyList(),
    /** Central revoked this till: it is locked, its facts are kept (doc 32 section 9). */
    val revoked: Boolean = false,
)

/**
 * The till's use cases (doc 26, 26A; doc 32): enrol, take the snapshot, sign in, open a session,
 * sell for cash, close the session with its Z-report, and upload the facts when central can be
 * reached. It writes every fact locally first, in one transaction with the counters that number
 * it, so the till sells the same with or without a network.
 *
 * What it trusts from central (wave 2, decision 2026-10-06-wave2-till-trust-and-durability): only
 * an https server (or this PC), a snapshot signed with the enrolled key that follows the version
 * it holds, and a revoke signed with that key for this device. What it keeps: every fact until it
 * is acknowledged and then for the retention window, every refusal central reports, the PIN
 * lock-out across restarts.
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
    private val basePolicy: TillPolicy = TillPolicy(),
    /** COOP_TILL_TRIAL_CASHIER=true: the trial's stand-in cashier may be offered (TWK-04). Off by default. */
    private val trialCashierAllowed: Boolean = false,
    /** The operator set the identity provider's address on this PC (COOP_TILL_TOKEN_ENDPOINT). */
    private val tokenEndpointSetLocally: Boolean = false,
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
    private var operatorsSeen = false
    private var revoked = false
    /** Why the stored identity was not loaded at start (enrolled over plain http before the rule). */
    private var identityProblem: String? = null
    /** The monotonic clock when the offset, and central's server_time, were last taken (this process only). */
    private var offsetElapsedAt: Long? = null
    private var serverTimeElapsedAt: Long? = null
    private var clockRefusalNoted = false

    private val policy: TillPolicy get() = basePolicy.withConfig(catalogue.config)

    /** Reads what the till already knows from its database. Call once at start. */
    suspend fun start() = lock.withLock {
        val stored = store.setting(Settings.DEVICE)?.let { json.decodeFromString(DeviceIdentity.serializer(), it) }
        identityProblem = null
        if (stored != null) {
            try {
                // An identity enrolled before the https rule is refused, not used (TWK-02): re-enrol.
                ServerAddress.requireTrusted(stored.serverUrl, "The server address this till enrolled with")
                requireTokenEndpoint(stored.serverUrl, stored.tokenEndpoint)
                device = stored
                central.identify(stored)
            } catch (e: TillRefusal) {
                identityProblem = "${e.message}. The office must issue a new enrolment code; enrol this till again over https."
            }
        }
        val snapshot = store.loadSnapshot()
        snapshotVersion = snapshot.version
        catalogue = Catalogue(snapshot, localPrices)
        operatorsSeen = store.setting(Settings.OPERATORS_SEEN) == "true" || catalogue.operators.isNotEmpty()
        revoked = store.setting(Settings.REVOKED) != null
        repairCounters()
        purgeAcknowledged()
        publishStatus()
    }

    val isEnrolled: Boolean get() = device != null
    val isRevoked: Boolean get() = revoked
    val shopLanguage: Language get() = catalogue.shop?.language ?: Language.EN

    /** Whether the sign-in screen may offer the trial's stand-in cashier (TWK-04). */
    val trialCashierOffered: Boolean
        get() = trialCashierAllowed && !operatorsSeen && catalogue.operators.isEmpty() && !revoked

    // ================================================================= enrolment (doc 32 section 2)

    /**
     * Enrols with the one-time code from the back office. The answer gives the till its identity,
     * its credential, central's signing key, its next device sequence and its receipt series; the
     * till goes on from the higher of its own counters and central's, so re-enrolling never reuses
     * a number. Central's key is trusted on first use, over https only (decision D-1).
     *
     * Enrolling again (a new code for the same device id) also ends a revoke: the kept outbox is
     * uploaded from the last acknowledged sequence on (decision D-3).
     */
    suspend fun enrol(serverUrl: String, deviceId: String, code: String, hardwareSerial: String): DeviceIdentity {
        val server = serverUrl.trim().trimEnd('/')
        ServerAddress.requireTrusted(server, "The server address")
        val answer = central.enrol(server, deviceId.trim(), code.trim(), hardwareSerial.trim(), appVersion)
        val rct = answer["series"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it.str("doc_type_code") == "RCT" }
            ?: throw TillRefusal("The enrolment answer carries no receipt (RCT) series for this till position")
        val credential = answer.getValue("credential").jsonObject
        val key = answer.getValue("signing_key").jsonObject
        val identity = DeviceIdentity(
            serverUrl = server,
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
        // The answer must not send the device's secret to another host than the one it enrolled with.
        requireTokenEndpoint(identity.serverUrl, identity.tokenEndpoint)
        lock.withLock {
            store.transaction {
                store.putSetting(Settings.DEVICE, json.encodeToString(DeviceIdentity.serializer(), identity))
                val seq = answer.getValue("next_device_seq").jsonPrimitive.long
                store.putSetting(Settings.NEXT_DEVICE_SEQ, maxOf(seq, counter(Settings.NEXT_DEVICE_SEQ)).toString())
                val number = rct.getValue("next_number").jsonPrimitive.long
                store.putSetting(Settings.NEXT_RECEIPT_NUMBER, maxOf(number, counter(Settings.NEXT_RECEIPT_NUMBER)).toString())
                store.putSetting(Settings.ENROLLED_AT, now().toEpochMilliseconds().toString())
                store.removeSetting(Settings.REVOKED)
                store.removeSetting(Settings.SYNC_STOPPED)
                store.removeSetting(Settings.BATCH_IN_FLIGHT)
            }
            device = identity
            revoked = false
            identityProblem = null
            central.identify(identity)
            publishStatus()
        }
        return identity
    }

    private fun requireTokenEndpoint(serverUrl: String, tokenEndpoint: String) {
        if (tokenEndpointSetLocally) return
        ServerAddress.requireTrusted(tokenEndpoint, "The identity provider's address in the enrolment answer")
        if (ServerAddress.host(tokenEndpoint) != ServerAddress.host(serverUrl)) {
            throw TillRefusal(
                "The enrolment answer names an identity provider on another host ($tokenEndpoint) than central ($serverUrl); " +
                    "set COOP_TILL_TOKEN_ENDPOINT on this PC if that is intended",
            )
        }
    }

    // ================================================================= the snapshot (doc 32 section 5)

    /**
     * Downloads the snapshot since the version the till holds, verifies it (the enrolled key,
     * signature, manifest, table hashes, and that it follows the version the till holds) and applies
     * it whole. A delta from another version is answered by asking for a full snapshot (since=0); a
     * snapshot that fails is refused and the till keeps selling with the one it has.
     *
     * @return the version in use afterwards
     */
    suspend fun refreshSnapshot(): Long {
        val identity = requireDevice()
        val held = lock.withLock { snapshotVersion }
        var delta = SnapshotDelta.fromJson(central.snapshot(held))
        var manifest = verifier.verify(delta, identity.locationId, identity.signingPublicKey, identity.signingKeyId)
        try {
            verifier.continuity(manifest, held)
        } catch (e: SnapshotNotContinuous) {
            delta = SnapshotDelta.fromJson(central.snapshot(0))
            manifest = verifier.verify(delta, identity.locationId, identity.signingPublicKey, identity.signingKeyId)
            verifier.continuity(manifest, held)
        }
        lock.withLock {
            val current = store.loadSnapshot()
            // Another refresh may have applied a version meanwhile: check against what is stored now.
            verifier.continuity(manifest, current.version)
            val confirmedAt = clock.now().toEpochMilliseconds().toString()
            if (delta.version == current.version && !delta.fullSnapshotRequired) {
                store.transaction { store.putSetting(Settings.LAST_SNAPSHOT_AT, confirmedAt) }
                publishStatus()
                return current.version
            }
            val next = current.apply(delta, businessDate())
            store.transaction {
                store.saveSnapshot(next)
                store.putSetting(Settings.LAST_SNAPSHOT_AT, confirmedAt)
                if (next.table("operator").isNotEmpty()) store.putSetting(Settings.OPERATORS_SEEN, "true")
            }
            snapshotVersion = next.version
            catalogue = Catalogue(next, localPrices)
            if (catalogue.operators.isNotEmpty()) operatorsSeen = true
            publishStatus()
            return next.version
        }
    }

    // ================================================================= signing in (26A section 8)

    /**
     * Signs [candidate] in with their PIN, checked against the Argon2id hash of the signed snapshot.
     * Wrong PINs in a row lock the till (per till, not per operator, so trying another operator does
     * not help) for the policy's time (26A section 8: five, fifteen minutes). The count and the
     * lock-out are kept in the database in the failure's transaction, so a restart does not reset
     * them, and the lock-out is an audit fact for central (TWK-03).
     */
    suspend fun signIn(candidate: Operator, pin: String) {
        lock.withLock {
            requireNotRevoked()
            requireNotLockedOut()
        }
        if (!checkPin(candidate, pin)) throw TillRefusal(lock.withLock { lockedOutMessage() } ?: "Wrong PIN")
        lock.withLock {
            store.transaction { store.putSetting(Settings.PIN_FAILURES, "0") }
            operator = candidate
        }
    }

    /**
     * Checks [pin] for [candidate] outside the lock (Argon2id takes a moment); a wrong PIN counts
     * toward the lock-out, a PIN record the till cannot read does not (TWK-10).
     */
    private suspend fun checkPin(candidate: Operator, pin: String): Boolean {
        val hash = candidate.pinHash ?: throw TillRefusal("${candidate.displayName} has no PIN set")
        val ok = try {
            pins.verify(pin, hash)
        } catch (e: UnreadablePinHash) {
            throw TillRefusal("${candidate.displayName}'s PIN record cannot be read; ask the office")
        }
        if (!ok) lock.withLock { countWrongPin() }
        return ok
    }

    private fun countWrongPin() {
        val at = now()
        val limit = policy.pinFailuresBeforeLock
        store.transaction {
            val failures = counterOr(Settings.PIN_FAILURES, 0).toInt() + 1
            if (failures >= limit) {
                val until = at + policy.pinLockFor
                store.putSetting(Settings.PIN_LOCKED_UNTIL, until.toEpochMilliseconds().toString())
                store.putSetting(Settings.PIN_FAILURES, "0")
                device?.let { record(Facts.PIN_LOCKOUT_EVENT, at, null, Facts.pinLockout(it, failures, until)) }
            } else {
                store.putSetting(Settings.PIN_FAILURES, failures.toString())
            }
        }
        publishStatus()
    }

    private fun requireNotLockedOut() {
        lockedOutMessage()?.let { throw TillRefusal(it) }
    }

    private fun lockedOutMessage(): String? {
        val until = store.setting(Settings.PIN_LOCKED_UNTIL)?.toLongOrNull()?.let { Instant.fromEpochMilliseconds(it) } ?: return null
        return if (now() < until) "Too many wrong PINs; try again after ${Times.printed(until, clock.zone)}" else null
    }

    /**
     * The trial's stand-in when the snapshot carries no operator with a PIN (the demo stack has no
     * till users yet): a cashier with a fixed id per till, as the backend's till simulator does. Only
     * when the PC opted in (COOP_TILL_TRIAL_CASHIER=true) and never once a snapshot has carried an
     * operator (TWK-04).
     */
    suspend fun signInTrialCashier(): Operator = lock.withLock {
        requireNotRevoked()
        if (!trialCashierAllowed) {
            throw TillRefusal("This till has no operator for this shop; the office must assign one with a PIN")
        }
        if (operatorsSeen || catalogue.operators.isNotEmpty()) {
            throw TillRefusal("This shop's snapshot has carried operators: sign in with an operator's PIN")
        }
        val id = store.setting(Settings.TRIAL_OPERATOR_ID) ?: ids.next().also { id ->
            store.transaction { store.putSetting(Settings.TRIAL_OPERATOR_ID, id) }
        }
        Operator(id, "Trial cashier", shopLanguage, null, emptyList()).also { operator = it }
    }

    fun signOut() {
        operator = null
    }

    // ================================================================= the session (doc 26 section 3.5)

    suspend fun openSession(floatAmount: Money): SessionRecord = lock.withLock {
        val identity = requireDevice()
        requireNotRevoked()
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
        val opened = store.transaction {
            val fact = record("till_session.opened.v1", now, cashier.userId, Facts.sessionOpened(identity, session))
            session.copy(openedSeq = fact.deviceSeq).also { store.saveSession(it) }
        }
        publishStatus()
        opened
    }

    suspend fun currentSession(): SessionRecord? = lock.withLock { store.openSession() }

    /** The business date a session opens on now, for the screen (null before the first session). */
    suspend fun currentBusinessDate(): LocalDate? = lock.withLock { store.setting(Settings.BUSINESS_DATE)?.let { LocalDate.parse(it) } }

    /** Today's date at the shop by the till's corrected clock. */
    suspend fun clockDate(): LocalDate = lock.withLock { Times.date(now(), clock.zone) }

    /**
     * A supervisor moves the till's business date back (decision D-4; CR-32-1 item 4): the till's
     * date is its cache of central's forward-only date, so a date opened from a wrong PC clock can be
     * corrected while no session is open. Receipts and sessions already issued keep the date they
     * carry. The supervisor is an operator with [TillPolicy.SUPERVISOR_PERMISSION] and their PIN (a
     * wrong one counts toward the lock-out); on a trial till with no operators, the trial cashier.
     */
    suspend fun correctBusinessDate(date: LocalDate, supervisor: Operator?, pin: String?) {
        lock.withLock {
            requireDevice()
            requireNotRevoked()
            if (store.openSession() != null) throw TillRefusal("Close the session first: the business date changes only between sessions")
            val current = store.setting(Settings.BUSINESS_DATE)?.let { LocalDate.parse(it) }
            if (current == null || date >= current) {
                throw TillRefusal("Only a move back needs a supervisor; the business date moves forward by itself when a session opens")
            }
            if (supervisor == null) {
                if (!trialCashierOffered) throw TillRefusal("A supervisor must sign the correction with their PIN")
            } else {
                if (TillPolicy.SUPERVISOR_PERMISSION !in supervisor.permissions) {
                    throw TillRefusal("${supervisor.displayName} may not correct the business date (${TillPolicy.SUPERVISOR_PERMISSION})")
                }
                requireNotLockedOut()
            }
        }
        if (supervisor != null && !checkPin(supervisor, pin.orEmpty())) {
            throw TillRefusal(lock.withLock { lockedOutMessage() } ?: "Wrong PIN")
        }
        lock.withLock {
            if (store.openSession() != null) throw TillRefusal("Close the session first: the business date changes only between sessions")
            store.transaction {
                if (supervisor != null) store.putSetting(Settings.PIN_FAILURES, "0")
                store.putSetting(Settings.BUSINESS_DATE, date.toString())
            }
            publishStatus()
        }
    }

    /**
     * Sells the basket for cash: numbers the receipt from the till position's series, writes it and
     * its bundle to the outbox in one transaction, and returns it for printing. The basket is
     * emptied only when the receipt is safely written.
     */
    suspend fun sellForCash(basket: Basket, tendered: Money): IssuedReceipt = lock.withLock {
        val identity = requireDevice()
        requireNotRevoked()
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
        val closing = session.copy(
            closedAt = Times.wholeSecond(now).epochSeconds,
            countedCash = counted,
            expectedCash = SessionRules.expectedCash(session, open.cashSales),
        )
        val closed = store.transaction {
            val fact = record("till_session.closed.v1", now, operator?.userId ?: session.operatorUserId, Facts.sessionClosed(identity, closing))
            closing.copy(closedSeq = fact.deviceSeq).also { store.saveSession(it) }
        }
        publishStatus()
        zReportOf(closed, receipts)
    }

    suspend fun zReport(sessionId: String): ZReport? = lock.withLock {
        store.session(sessionId)?.let { zReportOf(it, store.receiptsOfSession(it.sessionId)) }
    }

    private fun zReportOf(session: SessionRecord, receipts: List<IssuedReceipt>): ZReport =
        ZReport.of(session, receipts, store.quarantinedAmong(ZReport.factsOf(session, receipts)))

    // ================================================================= problems for the office

    /** The till's recorded problems, newest first. */
    suspend fun problems(limit: Int = 100): List<Anomaly> = lock.withLock { store.anomalies(limit) }

    /** The supervisor has seen the problems: the status bar count goes back to zero; the rows stay. */
    suspend fun markProblemsSeen() = lock.withLock {
        store.transaction { store.markAnomaliesSeen() }
        publishStatus()
    }

    // ================================================================= upload (doc 32 section 3)

    /**
     * Uploads what waits in the outbox, batch after batch, until it is empty, central says to
     * wait, or central cannot be reached. Then checks for a newer snapshot. Never throws for a
     * network failure: the till is simply offline.
     *
     * Central's answers are kept (TWK-05): each QUARANTINED outcome becomes a local problem, a
     * RESEND_FROM or a 409 sync.sequence_gap sends the retained facts again, FLOOR_NOTICE becomes a
     * banner, and a revoke signed with the enrolled key locks the till and keeps its outbox.
     *
     * @return how many facts central acknowledged
     */
    suspend fun syncOnce(batchSize: Int = 50): Int {
        val identity = device ?: return 0
        var acknowledged = 0
        var resends = 0
        try {
            if (lock.withLock { revoked }) {
                // A revoked till only asks whether the office has reinstated it (decision D-3).
                heartbeat(identity)
                lock.withLock {
                    store.transaction { store.removeSetting(Settings.REVOKED) }
                    revoked = false
                    publishStatus()
                }
            }
            while (true) {
                val batch = lock.withLock { nextBatch(batchSize) } ?: break
                val answer = try {
                    central.upload(batch.json)
                } catch (e: CentralRefused) {
                    val expected = e.params["expected_seq"]?.jsonPrimitive?.longOrNull
                    if (e.status == 409 && e.code == "sync.sequence_gap" && expected != null) {
                        val again = resends < MAX_RESENDS &&
                            lock.withLock { resendFrom(expected, "central's cursor expects device sequence $expected (409 sync.sequence_gap)") }
                        if (again) {
                            resends++
                            continue
                        }
                        lock.withLock { publishStatus(online = true, message = "Central expects facts from $expected; see the problems") }
                        return acknowledged
                    }
                    throw e
                }
                if (answer is UploadAnswer.RateLimited) {
                    lock.withLock { publishStatus(online = true, message = "Central is busy; the till tries again in ${answer.retryAfterSeconds} s") }
                    return acknowledged
                }
                answer as UploadAnswer.Acknowledged
                val resend = lock.withLock {
                    store.transaction {
                        store.acknowledgeUpTo(answer.lastAppliedSeq, rawMillis())
                        store.putSetting(Settings.LAST_ACKNOWLEDGED, answer.lastAppliedSeq.toString())
                        store.putSetting(Settings.LAST_SYNC_AT, clock.now().epochSeconds.toString())
                        store.removeSetting(Settings.SYNC_STOPPED)
                        noteOutcomes(answer.outcomes)
                        // Central applied only part of the batch; the rest goes again in a new one.
                        if (answer.lastAppliedSeq < batch.last) store.removeSetting(Settings.BATCH_IN_FLIGHT)
                    }
                    noteClock(answer.clockOffsetMs, answer.serverTime)
                    handleInstructions(answer.instructions, fromAck = true)
                }
                acknowledged += (answer.lastAppliedSeq - batch.first + 1).coerceIn(0, batch.count.toLong()).toInt()
                if (resend && resends < MAX_RESENDS) {
                    resends++
                    continue
                }
                if (answer.lastAppliedSeq < batch.first) {
                    // Central took none of it (it waits for an earlier fact): try again on the next round.
                    lock.withLock { publishStatus(online = true, message = "Central has not applied the batch from ${batch.first} yet") }
                    return acknowledged
                }
            }
            val latest = heartbeat(identity)
            if (latest != null && latest > lock.withLock { snapshotVersion }) refreshSnapshot()
            lock.withLock {
                purgeAcknowledged()
                publishStatus(online = true, message = null)
            }
        } catch (e: CentralUnreachable) {
            lock.withLock { publishStatus(online = false, message = "Offline: selling continues; ${e.message}") }
        } catch (e: CentralRefused) {
            lock.withLock { onRefused(e) }
        }
        return acknowledged
    }

    private fun onRefused(e: CentralRefused) {
        when {
            e.status == 403 && e.params["revoke"] != null -> {
                val message = if (honourRevoke(e.params["revoke"] as? JsonObject)) {
                    "Central revoked this till; selling is locked and its unsent facts are kept"
                } else {
                    // The design signs the revoke so that a forged one cannot take a shop off the air.
                    "Central refused (403) with a revoke this till could not verify; it is ignored"
                }
                publishStatus(online = true, message = message)
            }
            e.status == 426 -> {
                store.transaction { store.putSetting(Settings.FLOOR_NOTICE, e.code ?: "sync.app_below_floor") }
                publishStatus(online = true, message = "Central sends no new snapshot to this version of the till; selling and uploading go on")
            }
            else -> publishStatus(online = true, message = "Central refused: ${e.problem.take(160)}")
        }
    }

    /**
     * Honours a revoke only when it is signed with the enrolled key, names this device and was
     * issued after the enrolment (doc 32 section 9; decision 2026-10-06 (3)). The till then locks,
     * deletes its snapshot rows and keeps its settings, receipts, sessions and outbox.
     */
    private fun honourRevoke(revoke: JsonObject?): Boolean {
        val identity = device ?: return false
        if (revoke == null) return false
        val type = revoke.str("type")
        val deviceId = revoke.str("device_id")
        val status = revoke.str("status")
        val issuedAt = revoke.str("issued_at")
        val signature = revoke.str("signature") ?: return false
        if (type != "REVOKE" || deviceId == null || status == null || issuedAt == null) return false
        if (!deviceId.equals(identity.deviceId, ignoreCase = true)) return false
        revoke.str("key_id")?.let { if (it != identity.signingKeyId) return false }
        // DeviceAuth.canonical: one field per line, each line ending in a newline.
        val text = "type=$type\ndevice_id=$deviceId\nstatus=$status\nissued_at=$issuedAt\n"
        if (!verifier.signedByCentral(text, signature, identity.signingPublicKey)) return false
        val issued = runCatching { Instant.parse(issuedAt) }.getOrNull() ?: return false
        val enrolledAt = store.setting(Settings.ENROLLED_AT)?.toLongOrNull()
        if (enrolledAt != null && issued.toEpochMilliseconds() <= enrolledAt) return false
        store.transaction {
            store.saveSnapshot(SnapshotState.EMPTY)
            store.putSetting(Settings.REVOKED, revoke.toString())
        }
        snapshotVersion = 0
        catalogue = Catalogue(SnapshotState.EMPTY, localPrices)
        operator = null
        revoked = true
        return true
    }

    private fun requireNotRevoked() {
        if (revoked) {
            throw TillRefusal("Central revoked this till. Its facts are kept; the office must reinstate it or issue a new enrolment code")
        }
    }

    /** Each QUARANTINED outcome is a problem the office must repair at central; the receipt stands as issued. */
    private fun noteOutcomes(outcomes: List<EventOutcome>) {
        val at = rawMillis()
        for (o in outcomes) {
            if (o.outcome != EventOutcome.QUARANTINED) continue
            store.addAnomaly(
                Anomaly(
                    kind = Anomaly.QUARANTINED, deviceSeq = o.deviceSeq, eventId = o.eventId, reason = o.reason,
                    detail = "Central refused fact ${o.deviceSeq} (${o.reason ?: "no reason"}); it stands as issued at the till, the office must repair it at central",
                    notedAt = at,
                ),
            )
        }
    }

    /**
     * RESEND_FROM and FLOOR_NOTICE (doc 32 section 3.4). FLAGGED outcomes have no channel to the
     * till (the back office sees flags; till/README.md, deviations).
     *
     * @return whether facts were made pending again to be sent at once
     */
    private fun handleInstructions(instructions: List<Instruction>, fromAck: Boolean): Boolean {
        var resend = false
        var floor: String? = null
        for (i in instructions) {
            when (i.type) {
                Instruction.RESEND_FROM -> i.fromSeq?.let { if (resendFrom(it, "central asked to resend from device sequence $it")) resend = true }
                Instruction.FLOOR_NOTICE -> floor = i.detail ?: "below the minimum version"
            }
        }
        store.transaction {
            if (floor != null) {
                store.putSetting(Settings.FLOOR_NOTICE, floor)
            } else if (fromAck) {
                store.removeSetting(Settings.FLOOR_NOTICE)
            }
        }
        return resend
    }

    /**
     * Makes the retained facts from [seq] pending again. When the oldest needed one has been purged
     * the till records the gap as a problem and stops sending until central's cursor moves (the
     * office's sequence reset, doc 32 section 8).
     *
     * @return whether there is something to send again
     */
    private fun resendFrom(seq: Long, why: String): Boolean {
        val newest = store.maxOutboxSeq() ?: return false
        if (seq > newest) return false
        if (store.outboxHolds(seq)) {
            store.transaction {
                store.unacknowledgeFrom(seq)
                store.removeSetting(Settings.BATCH_IN_FLIGHT)
            }
            return true
        }
        val days = policy.outboxRetention.inWholeDays
        val detail = "$why, but the till keeps acknowledged facts for $days days and no longer holds $seq; the office must record the gap (doc 32 section 8)"
        store.transaction {
            store.addAnomaly(Anomaly(Anomaly.RESEND_IMPOSSIBLE, seq, null, "PURGED", detail, rawMillis()))
            store.putSetting(Settings.SYNC_STOPPED, detail)
        }
        return false
    }

    private class Batch(val count: Int, val json: JsonObject, val first: Long, val last: Long)

    /** The oldest pending facts as one batch, or null when nothing waits. */
    private fun nextBatch(batchSize: Int): Batch? {
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
            // The raw clock: central's clock_offset_ms is measured against it.
            put("device_clock", Times.iso(clock.now()))
            put("events", JsonArray(pending.map { json.parseToJsonElement(it.json) }))
        }
        return Batch(pending.size, batch, first, last)
    }

    /**
     * The heartbeat (doc 32 section 6): reports the till's state, takes central's clock offset and
     * instructions, and returns central's snapshot version. A refusal other than a revoke, or no
     * answer, is not an error here: the facts are already uploaded.
     */
    private suspend fun heartbeat(identity: DeviceIdentity): Long? {
        val report = lock.withLock {
            buildJsonObject {
                put("app_version", appVersion)
                put("snapshot_version", snapshotVersion)
                put("pending_event_count", store.pendingCount())
                put("last_acknowledged_seq", counterOr(Settings.LAST_ACKNOWLEDGED, 0))
                put("device_clock", Times.iso(clock.now()))
                put("open_session", store.openSession() != null)
            }
        }
        val answer = try {
            central.heartbeat(report)
        } catch (e: CentralRefused) {
            if (e.status == 403 && e.params["revoke"] != null) throw e
            if (lock.withLock { revoked }) throw e
            return null
        } catch (e: CentralUnreachable) {
            if (lock.withLock { revoked }) throw e
            return null
        }
        lock.withLock {
            val serverTime = answer.str("server_time")?.let { runCatching { Instant.parse(it) }.getOrNull() }
            noteClock(answer["clock_offset_ms"]?.jsonPrimitive?.longOrNull, serverTime)
            val instructions = (answer["instructions"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map {
                Instruction(it.str("type") ?: "", it["from_seq"]?.jsonPrimitive?.longOrNull, it.str("detail"))
            }
            handleInstructions(instructions, fromAck = false)
            publishStatus()
        }
        return answer["snapshot_version"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    }

    // ================================================================= the clock (TWK-07, decision D-4)

    /**
     * Takes central's measure of the PC's clock from an ack or a heartbeat. An offset beyond the
     * policy's bound is refused: the PC's clock is kept, and the office is told to set it.
     */
    private fun noteClock(offsetMs: Long?, serverTime: Instant?) {
        store.transaction {
            if (serverTime != null) {
                store.putSetting(Settings.LAST_SERVER_TIME, serverTime.toEpochMilliseconds().toString())
                serverTimeElapsedAt = clock.elapsedMillis()
            }
            if (offsetMs == null) return@transaction
            if (abs(offsetMs) > policy.maxClockOffset.inWholeMilliseconds) {
                store.removeSetting(Settings.CLOCK_OFFSET_MS)
                store.removeSetting(Settings.CLOCK_OFFSET_AT)
                if (!clockRefusalNoted) {
                    clockRefusalNoted = true
                    store.addAnomaly(
                        Anomaly(
                            Anomaly.CLOCK, null, null, "OFFSET_TOO_LARGE",
                            "Central says this PC's clock is ${offsetMs.milliseconds} off, more than ${policy.maxClockOffset}; " +
                                "the till does not correct it: set the PC's date and time",
                            rawMillis(),
                        ),
                    )
                }
                return@transaction
            }
            clockRefusalNoted = false
            store.putSetting(Settings.CLOCK_OFFSET_MS, offsetMs.toString())
            store.putSetting(Settings.CLOCK_OFFSET_AT, rawMillis().toString())
            offsetElapsedAt = clock.elapsedMillis()
        }
    }

    /**
     * The till's clock corrected by the offset central last measured (TillEvent.occurred_at). The
     * offset is dropped, with a problem recorded, when the PC's clock was moved since it was measured:
     * it reads earlier than at the measurement, or it moved more than the policy's tolerance beside
     * the monotonic clock. The next heartbeat measures again.
     */
    private fun now(): Instant {
        val raw = clock.now()
        val offset = store.setting(Settings.CLOCK_OFFSET_MS)?.toLongOrNull() ?: return raw
        val at = store.setting(Settings.CLOCK_OFFSET_AT)?.toLongOrNull() ?: return raw + offset.milliseconds
        val rawMs = raw.toEpochMilliseconds()
        val elapsed = clock.elapsedMillis()
        val elapsedAt = offsetElapsedAt
        val backwards = rawMs < at
        val jumped = elapsed != null && elapsedAt != null &&
            abs((rawMs - at) - (elapsed - elapsedAt)) > policy.clockJumpTolerance.inWholeMilliseconds
        if (backwards || jumped) {
            store.transaction {
                store.removeSetting(Settings.CLOCK_OFFSET_MS)
                store.removeSetting(Settings.CLOCK_OFFSET_AT)
                store.addAnomaly(
                    Anomaly(
                        Anomaly.CLOCK, null, null, if (backwards) "CLOCK_BACKWARDS" else "CLOCK_JUMP",
                        "The PC's clock was moved (it read ${Times.iso(Instant.fromEpochMilliseconds(at))} when central measured it, " +
                            "now ${Times.iso(raw)}); central's correction is dropped until the next heartbeat",
                        rawMs,
                    ),
                )
            }
            offsetElapsedAt = null
            return raw
        }
        return raw + offset.milliseconds
    }

    private fun rawMillis(): Long = clock.now().toEpochMilliseconds()

    /**
     * The business date a new session opens on: today's date at the shop, never earlier than the
     * date the till already opened (a supervisor moves it back, [correctBusinessDate]). When it moves
     * on, rows held for the new day go live (doc 32 section 5.2). A date more than the policy's lead
     * after central's last server_time is refused when the till synced recently (decision D-4).
     */
    private fun openBusinessDay(now: Instant): LocalDate {
        val today = Times.date(now, clock.zone)
        val current = store.setting(Settings.BUSINESS_DATE)?.let { LocalDate.parse(it) }
        val date = if (current == null || today > current) today else current
        requireNotAheadOfCentral(date)
        if (date != current) {
            store.transaction {
                store.putSetting(Settings.BUSINESS_DATE, date.toString())
                val snapshot = store.loadSnapshot()
                if (snapshot.held.isNotEmpty()) {
                    val next = snapshot.dayOpen(date)
                    store.saveSnapshot(next)
                    catalogue = Catalogue(next, localPrices)
                }
            }
        }
        return date
    }

    private fun requireNotAheadOfCentral(date: LocalDate) {
        val server = store.setting(Settings.LAST_SERVER_TIME)?.toLongOrNull()?.let { Instant.fromEpochMilliseconds(it) } ?: return
        // How long ago central answered: by the monotonic clock in this process (a hand-set PC clock
        // cannot hide it), else by the PC's clock.
        val elapsed = clock.elapsedMillis()
        val elapsedAt = serverTimeElapsedAt
        val sinceSync = if (elapsed != null && elapsedAt != null) {
            elapsed - elapsedAt
        } else {
            val lastSync = store.setting(Settings.LAST_SYNC_AT)?.toLongOrNull() ?: return
            rawMillis() - lastSync * 1000
        }
        if (sinceSync > policy.recentSync.inWholeMilliseconds) return
        val latest = Times.date(server + policy.businessDateLead, clock.zone)
        if (date > latest) {
            throw TillRefusal(
                "The business date $date is more than a day after central's date (${Times.date(server, clock.zone)}). " +
                    "Check the PC's date and time; a supervisor can move the till's business date back",
            )
        }
    }

    // ================================================================= durability (TWK-06)

    /**
     * After a power cut the counters must be above every number already used: a receipt number or
     * device sequence issued twice is a sale lost at central. Moves a counter that is not, and
     * records it as a problem.
     */
    private fun repairCounters() {
        if (store.setting(Settings.DEVICE) == null) return
        val at = rawMillis()
        store.transaction {
            val maxNumber = store.maxReceiptNumber()
            val nextNumber = counter(Settings.NEXT_RECEIPT_NUMBER)
            if (maxNumber != null && nextNumber <= maxNumber) {
                store.putSetting(Settings.NEXT_RECEIPT_NUMBER, (maxNumber + 1).toString())
                store.addAnomaly(
                    Anomaly(Anomaly.COUNTER_REPAIRED, null, null, "RECEIPT_NUMBER", "The next receipt number was $nextNumber but $maxNumber is already issued; moved to ${maxNumber + 1}", at),
                )
            }
            val maxSeq = maxOf(store.maxOutboxSeq() ?: 0, counterOr(Settings.LAST_ACKNOWLEDGED, 0))
            val nextSeq = counter(Settings.NEXT_DEVICE_SEQ)
            if (maxSeq > 0 && nextSeq <= maxSeq) {
                store.putSetting(Settings.NEXT_DEVICE_SEQ, (maxSeq + 1).toString())
                store.addAnomaly(
                    Anomaly(Anomaly.COUNTER_REPAIRED, null, null, "DEVICE_SEQ", "The next device sequence was $nextSeq but $maxSeq is already used; moved to ${maxSeq + 1}", at),
                )
            }
        }
    }

    /** Acknowledged facts older than the retention window go (doc 32 section 3.4; S8, DR-3). */
    private fun purgeAcknowledged() {
        store.transaction { store.purgeAcknowledged(rawMillis() - policy.outboxRetention.inWholeMilliseconds) }
    }

    // ================================================================= helpers

    private fun requireDevice(): DeviceIdentity = device ?: throw TillRefusal(identityProblem ?: "Enrol this till first")

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
            problems = store.unseenAnomalyCount(),
            banners = banners(),
            revoked = revoked,
        )
    }

    /** What a supervisor must see on every screen. */
    private fun banners(): List<String> = buildList {
        identityProblem?.let { add(it) }
        if (revoked) {
            add("Central revoked this till. Selling is locked; its unsent facts are kept. The office must reinstate it or issue a new enrolment code.")
        }
        store.setting(Settings.FLOOR_NOTICE)?.let {
            add("This till's version ($appVersion) is below central's minimum: central keeps taking its sales but sends no new prices. The office must update it.")
        }
        store.setting(Settings.SYNC_STOPPED)?.let { add("Uploading is stopped: $it") }
        val confirmed = store.setting(Settings.LAST_SNAPSHOT_AT)?.toLongOrNull()
        if (confirmed != null && snapshotVersion > 0) {
            val age = rawMillis() - confirmed
            if (age > policy.snapshotStaleness.inWholeMilliseconds) {
                val on = Times.printed(Instant.fromEpochMilliseconds(confirmed), clock.zone)
                add("The snapshot was last confirmed by central on $on: prices and ceilings may be out of date.")
            }
        }
    }

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private companion object {
        /** Resends (RESEND_FROM, 409) in one round, so a central that keeps asking cannot loop the till. */
        const val MAX_RESENDS = 3
    }
}
