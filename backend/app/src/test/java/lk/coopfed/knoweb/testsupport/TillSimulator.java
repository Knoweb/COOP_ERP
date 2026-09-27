package lk.coopfed.knoweb.testsupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.sync.web.generated.Heartbeat;
import lk.coopfed.knoweb.kernel.sync.web.generated.HeartbeatResponse;
import lk.coopfed.knoweb.kernel.sync.web.generated.SnapshotDelta;
import lk.coopfed.knoweb.kernel.sync.web.generated.SnapshotRow;
import lk.coopfed.knoweb.kernel.sync.web.generated.SnapshotTable;
import lk.coopfed.knoweb.kernel.sync.web.generated.SnapshotTombstone;
import lk.coopfed.knoweb.kernel.sync.web.generated.SyncAck;
import lk.coopfed.knoweb.kernel.sync.web.generated.SyncBatch;
import lk.coopfed.knoweb.kernel.sync.web.generated.TillEvent;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * A till as central sees it (doc 32 section 11: "a till simulator central is load-tested with";
 * 19A section 8), for the conformance suite and for any module test that needs a till. It speaks
 * the sync contract over HTTP with a device token, sending and reading the request and answer
 * types generated from openapi/sync.yaml, and keeps what a real till keeps:
 *
 * <ul>
 *   <li>an <b>outbox</b> of its own facts, numbered by a dense device sequence and kept until
 *       central acknowledges them ({@link #record}, {@link #uploadOnce}, {@link #drain});
 *   <li>a <b>snapshot</b> of central's reference data at one version, applied whole: a download
 *       is staged, its signed manifest and every table's hash are checked, and only then does it
 *       replace the live tables and the version, in one step ({@link #refreshSnapshot}). A power
 *       cut in between ({@link #powerCutAfterRows}) leaves the old version live, as on a till;
 *   <li>rows <b>held</b> for a later business date (apply_from), activated at that day's open
 *       ({@link #dayOpen}).
 * </ul>
 *
 * <p>The hash and signature checks are written here from the rules in the slice
 * (SnapshotDelta.manifest), not borrowed from the kernel's code, so that a mismatch between the
 * rules and the kernel shows up as a till would see it.
 */
public final class TillSimulator {

    /** Central said to wait (429 sync.rate_limited): try again after {@code retryAfter}. */
    public static final class RateLimited extends RuntimeException {
        public final Duration retryAfter;

        RateLimited(Duration retryAfter) {
            super("rate limited, retry after " + retryAfter);
            this.retryAfter = retryAfter;
        }
    }

    /** The power went while the snapshot was being staged. */
    public static final class PowerCut extends RuntimeException {
        PowerCut() {
            super("power cut while applying the snapshot");
        }
    }

    /** Central answered something a till cannot go on from. */
    public static final class Refused extends RuntimeException {
        public final int status;
        public final JsonNode problem;

        Refused(int status, JsonNode problem) {
            super("central answered " + status + ": " + problem);
            this.status = status;
            this.problem = problem;
        }
    }

    /** A row of the live or held snapshot. */
    public record HeldRow(String table, UUID rowId, LocalDate applyFrom, Map<String, Object> data) {}

    /** Keys sorted at every level and no whitespace: the "data" of the hash rule. */
    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private final ObjectMapper json;
    private final UUID deviceId;
    private final UUID locationId;
    private final PublicKey centralKey;
    private TestRestTemplate central;

    // ---- the outbox ----
    private final TreeMap<Long, TillEvent> outbox = new TreeMap<>();
    private long nextSeq = 1;
    private long lastAcknowledged = 0;
    private String appVersion = "1.0.0";

    // ---- the snapshot ----
    private long snapshotVersion = 0;
    private Map<String, Map<UUID, Map<String, Object>>> live = new TreeMap<>();
    private List<HeldRow> held = new ArrayList<>();
    private LocalDate businessDate = LocalDate.now();
    private int powerCutAfterRows = -1;

    /**
     * @param central     the instance the till talks to (its base URL); {@link #talkTo} changes it
     * @param json        the application's object mapper, which reads the generated types
     * @param signingKey  central's snapshot signing key as the enrolment answer gave it (X.509, base64)
     */
    public TillSimulator(
            TestRestTemplate central, ObjectMapper json, UUID deviceId, UUID locationId, String signingKey) {
        this.central = central;
        this.json = json;
        this.deviceId = deviceId;
        this.locationId = locationId;
        try {
            this.centralKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(signingKey)));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not an Ed25519 public key", e);
        }
    }

    /** From now on the till talks to another instance (the load balancer sent it elsewhere). */
    public TillSimulator talkTo(TestRestTemplate instance) {
        this.central = instance;
        return this;
    }

    public UUID deviceId() {
        return deviceId;
    }

    /** The application version the till reports from now on (doc 31: the floor is compared with it). */
    public TillSimulator runningVersion(String version) {
        this.appVersion = version;
        return this;
    }

    /**
     * The till's local database lost its unacknowledged outbox rows (doc 32 section 7, "till outbox
     * lost"): the counter survives, the rows central still asks for do not. Only the sequence
     * reset of an administrator (doc 32 section 8) lets it upload again.
     *
     * @return how many events were lost
     */
    public int loseUnsentEvents() {
        int lost = outbox.size();
        outbox.clear();
        return lost;
    }

    // ================================================================= the outbox (doc 32 section 3)

    /** The till records a fact: the next device sequence number, kept until acknowledged. */
    public long record(String eventType, Map<String, Object> payload) {
        long seq = nextSeq++;
        TillEvent event =
                new TillEvent(UUID.randomUUID(), eventType, seq, Instant.now(), payload).actorUserId(UUID.randomUUID());
        outbox.put(seq, event);
        return seq;
    }

    /** Records {@code count} harmless facts. */
    public void recordSales(int count) {
        for (int i = 0; i < count; i++) {
            record("till.test_fact.v1", Map.of("kind", "simulated", "amount", 100 + i));
        }
    }

    /** Where the device token comes from; the test identity provider's unless a real one is given. */
    private java.util.function.Supplier<HttpHeaders> auth;

    /**
     * From now on each call carries these headers (a device token from a real identity provider,
     * for the demo against the local stack), instead of the test provider's.
     */
    public TillSimulator authenticatedBy(java.util.function.Supplier<HttpHeaders> headers) {
        this.auth = headers;
        return this;
    }

    /** The enrolment answer's next_device_seq: a till enrolled again goes on from where central is. */
    public TillSimulator startingAt(long nextDeviceSeq) {
        this.nextSeq = nextDeviceSeq;
        this.lastAcknowledged = nextDeviceSeq - 1;
        return this;
    }

    // ============================================================ selling (doc 26; 26A sections 6 and 8)

    /** A line rung up at the counter: a scanned barcode, a quantity and the price the till charged. */
    public record Sale(String barcode, java.math.BigDecimal qty, java.math.BigDecimal unitPrice) {}

    private UUID ownerEntityId;
    private UUID tillPositionId;
    private UUID seriesId;
    private String numberPrefix;
    private long nextReceiptNumber = 1;
    private UUID operator = UUID.randomUUID();
    private UUID sessionId;
    private java.math.BigDecimal sessionFloat = java.math.BigDecimal.ZERO;
    private java.math.BigDecimal cashTaken = java.math.BigDecimal.ZERO;

    /**
     * Who the till sells as: its entity, its position and the receipt series it holds (the RCT
     * series of its till position, doc 24B; a real till learns it at enrolment), and the prefix
     * its receipt numbers print with.
     */
    public TillSimulator sellsAs(UUID entity, UUID position, UUID series, String prefix) {
        this.ownerEntityId = entity;
        this.tillPositionId = position;
        this.seriesId = series;
        this.numberPrefix = prefix;
        return this;
    }

    /** The series' next number as the enrolment answer gave it. */
    public TillSimulator numberingFrom(long nextNumber) {
        this.nextReceiptNumber = nextNumber;
        return this;
    }

    /** The cashier signs in and opens a session with a float (doc 26 section 4.2): till_session.opened.v1. */
    public UUID openSession(java.math.BigDecimal floatAmount) {
        sessionId = UUID.randomUUID();
        sessionFloat = floatAmount;
        cashTaken = java.math.BigDecimal.ZERO;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", sessionId.toString());
        payload.put("till_position_id", String.valueOf(tillPositionId));
        payload.put("operator_user_id", operator.toString());
        payload.put("business_date", businessDate.toString());
        payload.put("opened_at", Instant.now().toString());
        payload.put("float_amount", floatAmount.toPlainString());
        record("till_session.opened.v1", payload);
        return sessionId;
    }

    /**
     * The three-tap sale: scan each barcode, take cash, complete. The item is found in the live
     * snapshot's {@code sku} table by its barcode (M2's contributor puts them inside the row); the
     * batch is left to central (the snapshot carries no lots yet), which deducts FEFO. The receipt
     * is numbered from the till's own series and uploaded as the bundle receipt.issued.v1 with its
     * content hash (doc 32 section 3.1).
     *
     * @return the receipt's document id
     */
    public UUID sell(List<Sale> sales) {
        if (sessionId == null || seriesId == null) {
            throw new IllegalStateException("Open a session and say what the till sells as first");
        }
        UUID documentId = UUID.randomUUID();
        long number = nextReceiptNumber++;
        long seq = nextSeq;
        List<Map<String, Object>> lines = new ArrayList<>();
        java.math.BigDecimal gross = java.math.BigDecimal.ZERO;
        int lineNo = 1;
        for (Sale sale : sales) {
            UUID sku = skuOfBarcode(sale.barcode());
            java.math.BigDecimal total =
                    sale.qty().multiply(sale.unitPrice()).setScale(2, java.math.RoundingMode.HALF_UP);
            gross = gross.add(total);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("line_no", lineNo++);
            line.put("sku_id", sku.toString());
            line.put("uom_code", "EA");
            line.put("qty", sale.qty().toPlainString());
            line.put("unit_price", sale.unitPrice().toPlainString());
            line.put("tax_amount", "0.00");
            line.put("line_total", total.toPlainString());
            lines.add(line);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("document_id", documentId.toString());
        document.put("doc_type_code", "RCT");
        document.put("series_id", seriesId.toString());
        document.put("doc_number", number);
        document.put("doc_number_display", numberPrefix + "-" + number);
        document.put("owner_entity_id", String.valueOf(ownerEntityId));
        document.put("location_id", locationId.toString());
        document.put("till_position_id", String.valueOf(tillPositionId));
        document.put("device_id", deviceId.toString());
        document.put("issued_at", Instant.now().toString());
        document.put("business_date", businessDate.toString());
        document.put("operator_user_id", operator.toString());
        document.put("currency", "LKR");
        document.put("net_amount", gross.toPlainString());
        document.put("tax_amount", "0.00");
        document.put("gross_amount", gross.toPlainString());
        document.put("origin", "OFFLINE");
        document.put("device_seq", seq);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("document", document);
        payload.put("lines", lines);
        payload.put("tenders", List.of(Map.of("seq", 1, "kind", "CASH", "amount", gross.toPlainString())));
        payload.put("session_id", sessionId.toString());
        cashTaken = cashTaken.add(gross);

        // The content hash by the kernel's rule (doc 18: SHA-256 over the canonical header and
        // lines), so that the gateway accepts the bundle; the conformance cases of the hash are
        // the gateway's own tests.
        String hash = lk.coopfed.knoweb.kernel.internal.document.BundleHash.of(
                json.valueToTree(document), json.valueToTree(lines));
        payload.put("content_hash", hash);
        long recorded = nextSeq++;
        outbox.put(
                recorded,
                new TillEvent(UUID.randomUUID(), "receipt.issued.v1", recorded, Instant.now(), payload)
                        .actorUserId(operator)
                        .aggregateType("receipt")
                        .aggregateId(documentId)
                        .contentHash(hash));
        return documentId;
    }

    /** Blind close (doc 26 section 4.2): the cashier counts, the till works out the variance. */
    public void closeSession(java.math.BigDecimal counted) {
        java.math.BigDecimal expected = sessionFloat.add(cashTaken);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", sessionId.toString());
        payload.put("till_position_id", String.valueOf(tillPositionId));
        payload.put("operator_user_id", operator.toString());
        payload.put("business_date", businessDate.toString());
        payload.put("closed_at", Instant.now().toString());
        payload.put("counted_cash", counted.toPlainString());
        payload.put("expected_cash", expected.toPlainString());
        payload.put("variance", counted.subtract(expected).toPlainString());
        record("till_session.closed.v1", payload);
        sessionId = null;
    }

    /** The SKU of a barcode in the live snapshot, as the till's scan layer finds it. */
    @SuppressWarnings("unchecked")
    private UUID skuOfBarcode(String barcode) {
        for (Map.Entry<UUID, Map<String, Object>> sku : table("sku").entrySet()) {
            Object barcodes = sku.getValue().get("barcodes");
            if (barcodes instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof Map<?, ?> row && barcode.equals(((Map<String, Object>) row).get("barcode"))) {
                        return sku.getKey();
                    }
                }
            }
        }
        throw new IllegalArgumentException("No item with barcode " + barcode + " in the snapshot");
    }

    public int pending() {
        return outbox.size();
    }

    public long lastAcknowledged() {
        return lastAcknowledged;
    }

    /**
     * Sends the oldest unacknowledged events, at most {@code maxEvents}, as one batch, and drops
     * from the outbox what the acknowledgement covers.
     *
     * @throws RateLimited when central says to wait
     * @throws Refused     on any other refusal (a gap, a batch in flight, a suspended device)
     */
    public SyncAck uploadOnce(int maxEvents) {
        if (outbox.isEmpty()) {
            throw new IllegalStateException("Nothing to upload");
        }
        List<Map<String, Object>> events = new ArrayList<>();
        for (TillEvent event : outbox.values()) {
            if (events.size() == maxEvents) {
                break;
            }
            events.add(json.convertValue(event, new TypeReference<Map<String, Object>>() {}));
        }
        long first = outbox.firstKey();
        SyncBatch batch = new SyncBatch(UUID.randomUUID(), first, first + events.size() - 1, appVersion, events)
                .snapshotVersionInUse(snapshotVersion)
                .deviceClock(Instant.now());
        ResponseEntity<String> answer = send(HttpMethod.POST, "/v1/sync/devices/" + deviceId + "/batches", batch);
        if (answer.getStatusCode().value() == 200) {
            SyncAck ack = read(answer.getBody(), SyncAck.class);
            acknowledge(ack.getLastAppliedSeq());
            return ack;
        }
        throw refusal(answer);
    }

    /**
     * Sends everything, batch after batch, waiting as told when central is busy, until the outbox
     * is empty or {@code deadline} passes.
     *
     * @return how many times central said to wait
     */
    public int drain(int maxEvents, Instant deadline) throws InterruptedException {
        int waits = 0;
        while (!outbox.isEmpty()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError(
                        "Till " + deviceId + " did not drain by the deadline; " + outbox.size() + " events left");
            }
            try {
                uploadOnce(maxEvents);
            } catch (RateLimited wait) {
                waits++;
                Thread.sleep(wait.retryAfter.toMillis());
            }
        }
        return waits;
    }

    /** Everything at or below {@code seq} is durably at central (doc 32 section 3.3). */
    private void acknowledge(long seq) {
        lastAcknowledged = Math.max(lastAcknowledged, seq);
        outbox.headMap(lastAcknowledged, true).clear();
    }

    /** Doc 32 section 6: the till reports and learns the snapshot version. */
    public HeartbeatResponse heartbeat() {
        Heartbeat report = new Heartbeat(appVersion)
                .snapshotVersion(snapshotVersion)
                .pendingEventCount(outbox.size())
                .lastAcknowledgedSeq(lastAcknowledged)
                .deviceClock(Instant.now());
        ResponseEntity<String> answer = send(HttpMethod.POST, "/v1/sync/devices/" + deviceId + "/heartbeat", report);
        if (answer.getStatusCode().value() == 200) {
            return read(answer.getBody(), HeartbeatResponse.class);
        }
        throw refusal(answer);
    }

    // ================================================================ the snapshot (doc 32 section 5)

    public long snapshotVersion() {
        return snapshotVersion;
    }

    /** The live rows of one table, as the till sells with them. */
    public Map<UUID, Map<String, Object>> table(String name) {
        return live.getOrDefault(name, Map.of());
    }

    /** The rows held for a later business date. */
    public List<HeldRow> held() {
        return List.copyOf(held);
    }

    public LocalDate businessDate() {
        return businessDate;
    }

    /** The next apply stops after this many rows, as if the power went. */
    public void powerCutAfterRows(int rows) {
        this.powerCutAfterRows = rows;
    }

    /**
     * Downloads the snapshot since the version the till holds and applies it whole: staged,
     * verified, then swapped in with its version. Selling goes on with the old version until the
     * swap (here: nothing live changes before it).
     *
     * @return the answer as central sent it
     */
    public SnapshotDelta refreshSnapshot() {
        ResponseEntity<String> answer =
                send(HttpMethod.GET, "/v1/sync/locations/" + locationId + "/snapshot?since=" + snapshotVersion, null);
        if (answer.getStatusCode().value() != 200) {
            throw refusal(answer);
        }
        SnapshotDelta delta = read(answer.getBody(), SnapshotDelta.class);
        apply(delta);
        return delta;
    }

    /** The shop opens a new business day: held rows dated on or before it go live. */
    public void dayOpen(LocalDate date) {
        this.businessDate = date;
        List<HeldRow> stillHeld = new ArrayList<>();
        for (HeldRow row : held) {
            if (row.applyFrom().isAfter(date)) {
                stillHeld.add(row);
            } else if (row.data() == null) {
                live.getOrDefault(row.table(), new HashMap<>()).remove(row.rowId());
            } else {
                live.computeIfAbsent(row.table(), t -> new LinkedHashMap<>()).put(row.rowId(), row.data());
            }
        }
        held = stillHeld;
    }

    /**
     * Applies a snapshot answer as the till does (verify, stage, swap). Public for the tests that
     * hand it an answer changed on the way; {@link #refreshSnapshot} is the normal path.
     */
    public void apply(SnapshotDelta delta) {
        // 1. Trust: the manifest is central's, and every table is what the manifest says.
        verifySignature(delta.getManifest(), delta.getSignature());
        JsonNode manifest = readTree(delta.getManifest());
        if (manifest.path("version").asLong() != delta.getVersion()
                || !manifest.path("location_id").asText().equals(locationId.toString())
                || manifest.path("full").asBoolean() != delta.getFullSnapshotRequired()) {
            throw new IllegalStateException("The manifest does not describe this snapshot");
        }
        if (manifest.path("tables").size() != delta.getTables().size()) {
            throw new IllegalStateException("The manifest and the snapshot name different tables");
        }
        delta.getTables().forEach((name, table) -> {
            String expected = manifest.path("tables").path(name).path("sha256").asText();
            if (!expected.equals(tableHash(table))) {
                throw new IllegalStateException("The hash of table " + name + " does not match its manifest");
            }
        });

        // 2. Stage: a copy of the live tables (nothing, for a full snapshot) with the changes on it.
        Map<String, Map<UUID, Map<String, Object>>> staging = new TreeMap<>();
        List<HeldRow> stagedHeld = new ArrayList<>();
        if (!delta.getFullSnapshotRequired()) {
            live.forEach((name, rows) -> staging.put(name, new LinkedHashMap<>(rows)));
            stagedHeld.addAll(held);
        }
        int applied = 0;
        for (Map.Entry<String, SnapshotTable> entry : delta.getTables().entrySet()) {
            String name = entry.getKey();
            Map<UUID, Map<String, Object>> rows = staging.computeIfAbsent(name, t -> new LinkedHashMap<>());
            for (SnapshotRow row : entry.getValue().getUpserts()) {
                applied = powerMayFail(applied);
                if (isLater(row.getApplyFrom())) {
                    stagedHeld.add(new HeldRow(name, row.getRowId(), row.getApplyFrom(), row.getData()));
                } else {
                    rows.put(row.getRowId(), row.getData());
                }
            }
            for (SnapshotTombstone gone : entry.getValue().getTombstones()) {
                applied = powerMayFail(applied);
                if (isLater(gone.getApplyFrom())) {
                    stagedHeld.add(new HeldRow(name, gone.getRowId(), gone.getApplyFrom(), null));
                } else {
                    rows.remove(gone.getRowId());
                }
            }
        }

        // 3. Swap, with the version, in one step (the till's one local transaction).
        live = staging;
        held = stagedHeld;
        snapshotVersion = delta.getVersion();
    }

    private int powerMayFail(int applied) {
        if (powerCutAfterRows >= 0 && applied >= powerCutAfterRows) {
            powerCutAfterRows = -1;
            throw new PowerCut();
        }
        return applied + 1;
    }

    private boolean isLater(LocalDate applyFrom) {
        return applyFrom != null && applyFrom.isAfter(businessDate);
    }

    // ---- the rules of SnapshotDelta.manifest, as the till implements them ----

    private void verifySignature(String manifest, String signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(centralKey);
            verifier.update(manifest.getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(Base64.getDecoder().decode(signature))) {
                throw new IllegalStateException("The snapshot manifest is not signed by central");
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String tableHash(SnapshotTable table) {
        List<String> lines = new ArrayList<>();
        table.getUpserts().stream()
                .sorted(Comparator.comparing((SnapshotRow row) -> row.getRowId().toString()))
                .forEach(row -> lines.add(
                        "U " + row.getRowId() + " " + date(row.getApplyFrom()) + " " + canonical(row.getData())));
        table.getTombstones().stream()
                .sorted(Comparator.comparing(
                        (SnapshotTombstone gone) -> gone.getRowId().toString()))
                .forEach(gone -> lines.add("D " + gone.getRowId() + " " + date(gone.getApplyFrom())));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String canonical(Object data) {
        try {
            return CANONICAL.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String date(LocalDate date) {
        return date == null ? "-" : date.toString();
    }

    // ---- HTTP ----

    private ResponseEntity<String> send(HttpMethod method, String path, Object body) {
        HttpHeaders headers = auth == null ? TestIdentityProvider.deviceHeaders(deviceId) : auth.get();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (method == HttpMethod.POST) {
            headers.set("Idempotency-Key", UUID.randomUUID().toString());
        }
        return central.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private RuntimeException refusal(ResponseEntity<String> answer) {
        int status = answer.getStatusCode().value();
        JsonNode problem = readTree(answer.getBody());
        if (status == 429) {
            return new RateLimited(Duration.ofSeconds(
                    problem.path("params").path("retry_after").asLong(1)));
        }
        return new Refused(status, problem);
    }

    private <T> T read(String body, Class<T> type) {
        try {
            return json.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Central's answer is not a " + type.getSimpleName() + ": " + body, e);
        }
    }

    private JsonNode readTree(String body) {
        try {
            return json.readTree(body == null ? "{}" : body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Not JSON: " + body, e);
        }
    }
}
