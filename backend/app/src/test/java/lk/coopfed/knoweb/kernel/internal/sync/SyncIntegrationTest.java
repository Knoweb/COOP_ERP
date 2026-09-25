package lk.coopfed.knoweb.kernel.internal.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What every sync test stands on: a shop of an MPCS with one till position and one device in M1's
 * tables (written here as the superuser: the fixture M1-06 will create through its handlers), the
 * device enrolled for sync (its cursor), and a till that speaks the contract of doc 32 over HTTP
 * with a device token: {@link #upload}, {@link #heartbeat}, {@link #event}. Each test class of
 * the conformance suite of doc 32 section 11 extends it.
 */
abstract class SyncIntegrationTest extends PostgresIntegrationTest {

    static final UUID ENTITY = UUID.fromString("0190a800-0000-7000-8000-000000000001");
    static final UUID OTHER_ENTITY = UUID.fromString("0190a800-0000-7000-8000-000000000002");
    static final UUID SHOP = UUID.fromString("0190a800-0000-7000-8000-000000000101");
    static final UUID OTHER_SHOP = UUID.fromString("0190a800-0000-7000-8000-000000000102");
    static final UUID POSITION = UUID.fromString("0190a800-0000-7000-8000-000000000201");
    static final UUID DEVICE = UUID.fromString("0190a800-0000-7000-8000-000000000301");
    static final UUID OPERATOR = UUID.fromString("0190a800-0000-7000-8000-000000000401");
    static final String SERIAL = "SN-K08-0001";

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    @Autowired
    DeviceDirectory directory;

    @BeforeEach
    void aShopWithAnEnrolledTill() {
        JdbcTemplate db = superuserJdbc();
        forgetTheShop(db);
        db.update(
                """
                insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, status,
                                            primary_till_position_id)
                values (?, ?, 'K08S1', 'SHOP', 'Sync test shop', 'ACTIVE', ?),
                       (?, ?, 'K08S2', 'SHOP', 'Another shop', 'ACTIVE', null)
                """,
                SHOP,
                ENTITY,
                POSITION,
                OTHER_SHOP,
                ENTITY);
        db.update(
                "insert into party.till_position (till_position_id, location_id, position_no, owner_entity_id) values (?, ?, 1, ?)",
                POSITION,
                SHOP,
                ENTITY);
        db.update(
                """
                insert into party.device (device_id, hardware_serial, device_kind, owner_entity_id,
                                          current_till_position_id, status, enrolled_at)
                values (?, ?, 'POS_TERMINAL', ?, ?, 'ACTIVE', now())
                """,
                DEVICE,
                SERIAL,
                ENTITY,
                POSITION);
        db.update("insert into kernel.device_sync_cursor (device_id, owner_entity_id) values (?, ?)", DEVICE, ENTITY);
        directory.invalidateAll();
    }

    @AfterEach
    void forgetTheShopAfterwards() {
        forgetTheShop(superuserJdbc());
        directory.invalidateAll();
    }

    /** Everything the sync tests write, in the order the keys allow. */
    static void forgetTheShop(JdbcTemplate db) {
        db.update("delete from kernel.event_outbox where owner_entity_id in (?, ?)", ENTITY, OTHER_ENTITY);
        for (String table : List.of(
                "kernel.sync_event",
                "kernel.sync_quarantine",
                "kernel.device_heartbeat",
                "kernel.device_enrolment_code",
                "kernel.device_sync_cursor",
                "kernel.change_log",
                "kernel.location_snapshot_version",
                "kernel.numbering_series")) {
            db.update("delete from " + table + " where owner_entity_id in (?, ?)", ENTITY, OTHER_ENTITY);
        }
        db.update("delete from party.device where owner_entity_id in (?, ?)", ENTITY, OTHER_ENTITY);
        db.update("delete from party.till_position where owner_entity_id in (?, ?)", ENTITY, OTHER_ENTITY);
        db.update("delete from party.location where owner_entity_id in (?, ?)", ENTITY, OTHER_ENTITY);
    }

    // ---- the till ----

    /** One till event in the envelope of doc 19 section 6.1, with a harmless payload. */
    ObjectNode event(long seq) {
        return event(seq, "till.test_fact.v1");
    }

    ObjectNode event(long seq, String type) {
        ObjectNode event = json.createObjectNode();
        event.put("event_id", Ids.next().toString());
        event.put("event_type", type);
        event.put("device_seq", seq);
        event.put("occurred_at", Instant.now().toString());
        event.put("actor_user_id", OPERATOR.toString());
        ObjectNode payload = event.putObject("payload");
        payload.put("amount", seq * 10);
        payload.put("kind", "conformance");
        return event;
    }

    /** Events first..last, one per sequence number. */
    List<ObjectNode> events(long first, long last) {
        List<ObjectNode> events = new ArrayList<>();
        for (long seq = first; seq <= last; seq++) {
            events.add(event(seq));
        }
        return events;
    }

    ObjectNode batch(UUID batchId, long firstSeq, List<ObjectNode> events) {
        ObjectNode batch = json.createObjectNode();
        batch.put("batch_id", batchId.toString());
        batch.put("first_seq", firstSeq);
        batch.put("last_seq", firstSeq + events.size() - 1);
        batch.put("app_version", "1.0.0");
        batch.put("snapshot_version_in_use", 0);
        batch.put("device_clock", Instant.now().toString());
        ArrayNode array = batch.putArray("events");
        events.forEach(array::add);
        return batch;
    }

    ResponseEntity<JsonNode> upload(ObjectNode batch) {
        return post("/v1/sync/devices/" + DEVICE + "/batches", batch, TestIdentityProvider.deviceHeaders(DEVICE));
    }

    ResponseEntity<JsonNode> upload(UUID batchId, long firstSeq, List<ObjectNode> events) {
        return upload(batch(batchId, firstSeq, events));
    }

    ResponseEntity<JsonNode> heartbeat(ObjectNode report) {
        return post("/v1/sync/devices/" + DEVICE + "/heartbeat", report, TestIdentityProvider.deviceHeaders(DEVICE));
    }

    ResponseEntity<JsonNode> post(String path, Object body, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    ResponseEntity<JsonNode> get(String path, HttpHeaders headers) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    // ---- what central holds ----

    long cursor() {
        return superuserJdbc()
                .queryForObject(
                        "select last_applied_seq from kernel.device_sync_cursor where device_id = ?",
                        Long.class,
                        DEVICE);
    }

    /** The device's events in the outbox, by sequence: what the module consumers receive. */
    List<Long> outboxSequences() {
        return superuserJdbc()
                .queryForList(
                        "select source_seq from kernel.event_outbox where source = ? order by source_seq",
                        Long.class,
                        DEVICE.toString());
    }

    static List<String> outcomes(JsonNode ack) {
        List<String> outcomes = new ArrayList<>();
        ack.path("outcomes").forEach(o -> outcomes.add(o.path("outcome").asText()));
        return outcomes;
    }
}
