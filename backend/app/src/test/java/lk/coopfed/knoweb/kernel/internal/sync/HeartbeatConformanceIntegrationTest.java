package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SyncAnomaly;
import lk.coopfed.knoweb.kernel.api.SyncStatus;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The heartbeat of doc 32 section 6 and the case of section 11 it serves: "Central point-in-time
 * restore: central re-requests from a lower cursor; the till resends retained rows; state
 * converges". Also the fleet-health fields, the clock offset and its drift review, and what
 * {@link SyncStatus} tells M1 from them.
 */
class HeartbeatConformanceIntegrationTest extends SyncIntegrationTest {

    @Autowired
    SystemScope systemScope;

    @Autowired
    SyncStatus syncStatus;

    @Test
    void theFleetHealthFieldsAreRecordedAndTheOffsetReturned() {
        Instant deviceClock = Instant.now().minusSeconds(90);
        ObjectNode report = report(0);
        report.put("device_clock", deviceClock.toString());
        report.put("pending_event_count", 12);
        report.put("oldest_pending_seq_age_s", 600);
        report.put("battery_pct", 81);
        report.put("storage_free_mb", 2048);
        report.put("device_uptime_s", 3600);
        report.put("open_session", true);
        report.putObject("peripherals").put("printer", "OK").put("scanner", "OK");

        ResponseEntity<JsonNode> response = heartbeat(report);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode answer = response.getBody();
        assertThat(answer.path("clock_offset_ms").asLong()).isBetween(89_000L, 120_000L);
        assertThat(answer.path("last_applied_seq").asLong()).isZero();
        assertThat(answer.path("snapshot_version").asLong()).isZero();
        assertThat(answer.path("urgent_change").asBoolean()).isFalse();
        assertThat(answer.path("instructions")).isEmpty();

        Map<String, Object> stored =
                superuserJdbc().queryForMap("select * from kernel.device_heartbeat where device_id = ?", DEVICE);
        assertThat(stored.get("app_version")).isEqualTo("1.0.0");
        assertThat(stored.get("pending_event_count")).isEqualTo(12);
        assertThat(((Number) stored.get("battery_pct")).intValue()).isEqualTo(81);
        assertThat(stored.get("open_session")).isEqualTo(true);
        assertThat(stored.get("peripherals").toString()).contains("printer");

        ScopeContext admin = SystemScope.own(ENTITY, null);
        SyncStatus.DeviceSyncState state = syncStatus.state(DEVICE, admin).orElseThrow();
        assertThat(state.lastSeenAt()).isNotNull();
        assertThat(state.pendingEventCount()).isEqualTo(12);
        assertThat(systemScope.inScope(admin, () -> syncStatus.drained(DEVICE))).isFalse();
        assertThat(syncStatus.state(DEVICE, SystemScope.own(OTHER_ENTITY, null)))
                .isEmpty();
    }

    @Test
    void aDeviceWithNothingPendingIsDrained() {
        upload(Ids.next(), 1, events(1, 2));
        ObjectNode report = report(2);
        report.put("pending_event_count", 0);
        heartbeat(report);

        assertThat(systemScope.inScope(SystemScope.own(ENTITY, null), () -> syncStatus.drained(DEVICE)))
                .isTrue();
    }

    @Test
    void aDriftingClockIsReviewedOnceAndNeverBlocks() {
        ObjectNode drifting = report(0);
        drifting.put("device_clock", Instant.now().minus(25, ChronoUnit.MINUTES).toString());

        assertThat(heartbeat(drifting).getStatusCode()).isEqualTo(HttpStatus.OK);
        drifting.put("device_clock", Instant.now().minus(25, ChronoUnit.MINUTES).toString());
        assertThat(heartbeat(drifting).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(kernel.committedAudit())
                .filteredOn(a -> a.eventType().equals("SYNC_CLOCK_DRIFT"))
                .hasSize(1);
        assertThat(kernel.committedEvents())
                .filteredOn(
                        e -> e instanceof SyncAnomaly anomaly && anomaly.kind().equals("CLOCK_DRIFT"))
                .hasSize(1);
    }

    @Test
    void centralPointInTimeRestoreCentralAsksForTheRetainedRowsAndStateConverges() {
        List<ObjectNode> retained = events(1, 10);
        upload(Ids.next(), 1, retained);
        assertThat(cursor()).isEqualTo(10);

        // Central is restored to a backup taken when it held six events: the cursor, the ledger
        // and the outbox all go back; the till still holds everything, acknowledged up to ten.
        superuserJdbc()
                .update(
                        "update kernel.device_sync_cursor set last_applied_seq = 6, last_batch_id = null,"
                                + " last_ack = null where device_id = ?",
                        DEVICE);
        superuserJdbc().update("delete from kernel.sync_event where device_id = ? and device_seq > 6", DEVICE);
        superuserJdbc()
                .update("delete from kernel.event_outbox where source = ? and source_seq > 6", DEVICE.toString());
        kernel.reset();

        JsonNode answer = heartbeat(report(10)).getBody();

        assertThat(answer.path("last_applied_seq").asLong()).isEqualTo(6);
        assertThat(answer.path("instructions").get(0).path("type").asText()).isEqualTo("RESEND_FROM");
        assertThat(answer.path("instructions").get(0).path("from_seq").asLong()).isEqualTo(7);
        assertThat(kernel.committedAudit())
                .filteredOn(a -> a.eventType().equals("SYNC_CURSOR_BEHIND"))
                .hasSize(1);

        // Asked again before the resend: the same instruction, no second review record.
        heartbeat(report(10));
        assertThat(kernel.committedAudit())
                .filteredOn(a -> a.eventType().equals("SYNC_CURSOR_BEHIND"))
                .hasSize(1);

        // The till resends its retained rows from seven (doc 32 S8: retention is why this works).
        JsonNode ack = upload(Ids.next(), 7, retained.subList(6, 10)).getBody();

        assertThat(ack.path("last_applied_seq").asLong()).isEqualTo(10);
        assertThat(outcomes(ack)).containsOnly("APPLIED");
        assertThat(ack.path("instructions")).isEmpty();
        assertThat(outboxSequences()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(heartbeat(report(10)).getBody().path("instructions")).isEmpty();
    }

    @Test
    void anUrgentChangeAfterTheDevicesVersionIsAnnounced() {
        superuserJdbc()
                .update(
                        "insert into kernel.location_snapshot_version (location_id, owner_entity_id, current_version) values (?, ?, 3)",
                        SHOP,
                        ENTITY);
        superuserJdbc()
                .update(
                        """
                insert into kernel.change_log (location_id, version, owner_entity_id, table_name, row_id, op, urgent)
                values (?, 2, ?, 'control_price', ?, 'UPSERT', false),
                       (?, 3, ?, 'control_price', ?, 'UPSERT', true)
                """,
                        SHOP,
                        ENTITY,
                        Ids.next(),
                        SHOP,
                        ENTITY,
                        Ids.next());

        ObjectNode behind = report(0);
        behind.put("snapshot_version", 2);
        JsonNode answer = heartbeat(behind).getBody();
        assertThat(answer.path("snapshot_version").asLong()).isEqualTo(3);
        assertThat(answer.path("urgent_change").asBoolean()).isTrue();

        ObjectNode current = report(0);
        current.put("snapshot_version", 3);
        assertThat(heartbeat(current).getBody().path("urgent_change").asBoolean())
                .isFalse();
    }

    ObjectNode report(long acknowledged) {
        ObjectNode report = json.createObjectNode();
        report.put("app_version", "1.0.0");
        report.put("snapshot_version", 0);
        report.put("last_acknowledged_seq", acknowledged);
        report.put("device_clock", Instant.now().toString());
        return report;
    }
}
