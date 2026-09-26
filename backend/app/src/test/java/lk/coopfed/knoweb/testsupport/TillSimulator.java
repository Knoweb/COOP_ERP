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
        SyncBatch batch = new SyncBatch(UUID.randomUUID(), first, first + events.size() - 1, "1.0.0", events)
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
        Heartbeat report = new Heartbeat("1.0.0")
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
        HttpHeaders headers = TestIdentityProvider.deviceHeaders(deviceId);
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
