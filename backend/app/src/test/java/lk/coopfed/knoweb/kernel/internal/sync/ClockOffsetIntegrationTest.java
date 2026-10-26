package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * PostgreSQL integration tests for TILL-DATA-10 (K-13):
 * Correcting offline till event timestamps using authenticated device clock offset information
 * while preventing double correction and preserving original occurred_local evidence.
 */
class ClockOffsetIntegrationTest extends SyncIntegrationTest {

    @Test
    void offlineEvent_withPositiveDrift_correctedInEventOutbox() {
        UUID eventId = Ids.next();
        Instant eventTime = Instant.parse("2026-09-29T10:00:00.789Z");
        LocalDateTime localTime = LocalDateTime.parse("2026-09-29T15:30:00");

        ObjectNode event = event(1);
        event.put("event_id", eventId.toString());
        event.put("occurred_at", eventTime.toString());
        event.put("occurred_local", "2026-09-29T15:30:00");
        event.put("offline_timed", true);

        // Till clock is 5 minutes (300,000 ms) behind server time
        Instant deviceClock = Instant.now().minusSeconds(300);
        ObjectNode batch = batch(Ids.next(), 1, List.of(event));
        batch.put("device_clock", deviceClock.toString());

        ResponseEntity<JsonNode> response = upload(batch);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outcomes(response.getBody())).containsExactly("APPLIED");

        Map<String, Object> outbox = outboxRow(eventId);
        Instant persistedOccurredAt = ((Timestamp) outbox.get("occurred_at")).toInstant();
        LocalDateTime persistedOccurredLocal = ((Timestamp) outbox.get("occurred_local")).toLocalDateTime();

        // Server time - deviceClock is approx +300,000 ms
        long shiftMs = Duration.between(eventTime, persistedOccurredAt).toMillis();
        assertThat(shiftMs).isBetween(295_000L, 315_000L);

        // Millisecond precision (.789s) is preserved through the correction
        assertThat(persistedOccurredAt.toEpochMilli() % 1000).isEqualTo((eventTime.toEpochMilli() + shiftMs) % 1000);

        // Original raw wall-clock reading in occurred_local is preserved unchanged
        assertThat(persistedOccurredLocal).isEqualTo(localTime);
    }

    @Test
    void offlineAuditEvent_correctedInAuditEventAndOutbox() {
        UUID eventId = Ids.next();
        UUID documentId = Ids.next();
        Instant eventTime = Instant.parse("2026-09-29T08:00:00.500Z");

        ObjectNode auditEvt = auditEvent(1, "DOCUMENT_ISSUED", "document", documentId);
        auditEvt.put("event_id", eventId.toString());
        auditEvt.put("occurred_at", eventTime.toString());
        auditEvt.put("occurred_local", "2026-09-29T13:30:00");
        auditEvt.put("offline_timed", true);
        ObjectNode payload = (ObjectNode) auditEvt.get("payload");
        payload.put("document_id", documentId.toString());
        payload.put("reason_code", "SALE");
        payload.putObject("after_state").put("status", "ISSUED");

        // Till clock is 2 minutes (120,000 ms) behind server time
        Instant deviceClock = Instant.now().minusSeconds(120);
        ObjectNode batch = batch(Ids.next(), 1, List.of(auditEvt));
        batch.put("device_clock", deviceClock.toString());

        ResponseEntity<JsonNode> response = upload(batch);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outcomes(response.getBody())).containsExactly("APPLIED");

        // Verify kernel.audit_event
        Map<String, Object> audit = auditRow(eventId);
        Instant auditOccurredAt = ((Timestamp) audit.get("occurred_at")).toInstant();
        long auditShiftMs = Duration.between(eventTime, auditOccurredAt).toMillis();
        assertThat(auditShiftMs).isBetween(115_000L, 135_000L);

        // Verify kernel.event_outbox
        Map<String, Object> outbox = outboxRow(eventId);
        Instant outboxOccurredAt = ((Timestamp) outbox.get("occurred_at")).toInstant();
        assertThat(outboxOccurredAt).isEqualTo(auditOccurredAt);
    }

    @Test
    void alreadyCorrectedEvent_withMatchingOffset_isNotShiftedTwice() {
        UUID eventId = Ids.next();
        // Event was recorded when till had +60,000 ms offset already applied
        Instant correctedEventTime = Instant.parse("2026-09-29T10:01:00.000Z");

        // Record heartbeat with matching +60,000 ms offset
        superuserJdbc()
                .update(
                        """
                insert into kernel.device_heartbeat (device_id, owner_entity_id, last_seen_at, app_version, clock_offset_ms)
                values (?, ?, now(), '1.0.0', 60000)
                on conflict (device_id) do update set clock_offset_ms = 60000, last_seen_at = now()
                """,
                        DEVICE,
                        ENTITY);

        ObjectNode event = event(1);
        event.put("event_id", eventId.toString());
        event.put("occurred_at", correctedEventTime.toString());
        event.put("occurred_local", "2026-09-29T15:30:00");
        event.put("offline_timed", false);
        event.put("clock_offset_ms", 60_000L);

        // Batch relies on matching stored offset (delta = 60,000 - 60,000 = 0)
        ObjectNode batch = batch(Ids.next(), 1, List.of(event));
        batch.remove("device_clock");

        ResponseEntity<JsonNode> response = upload(batch);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outcomes(response.getBody())).containsExactly("APPLIED");

        Map<String, Object> outbox = outboxRow(eventId);
        Instant persistedOccurredAt = ((Timestamp) outbox.get("occurred_at")).toInstant();

        // Exact match: the event was NOT shifted twice to 10:02:00
        assertThat(persistedOccurredAt).isEqualTo(correctedEventTime);
    }

    @Test
    void eventWithPreviousOffset_andAccumulatedDrift_adjustsByDeltaOnly() {
        UUID eventId = Ids.next();
        Instant eventTime = Instant.parse("2026-09-29T10:01:00.000Z");

        ObjectNode event = event(1);
        event.put("event_id", eventId.toString());
        event.put("occurred_at", eventTime.toString());
        event.put("occurred_local", "2026-09-29T15:30:00");
        event.put("offline_timed", false);
        event.put("clock_offset_ms", 60_000L); // Applied +60s at event time

        // By upload time, drift grew to 150 seconds (150,000 ms)
        Instant deviceClock = Instant.now().minusSeconds(150);
        ObjectNode batch = batch(Ids.next(), 1, List.of(event));
        batch.put("device_clock", deviceClock.toString());

        ResponseEntity<JsonNode> response = upload(batch);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outcomes(response.getBody())).containsExactly("APPLIED");

        Map<String, Object> outbox = outboxRow(eventId);
        Instant persistedOccurredAt = ((Timestamp) outbox.get("occurred_at")).toInstant();

        // Delta = currentOffset (~150s) - eventOffset (60s) = ~90s
        long deltaShiftMs = Duration.between(eventTime, persistedOccurredAt).toMillis();
        assertThat(deltaShiftMs).isBetween(85_000L, 105_000L);
    }

    @Test
    void legacyEvent_withoutMetadata_retainsOriginalOccurredAt() {
        UUID eventId = Ids.next();
        Instant legacyTime = Instant.parse("2026-09-29T10:00:00.123Z");

        ObjectNode event = event(1);
        event.put("event_id", eventId.toString());
        event.put("occurred_at", legacyTime.toString());
        event.put("occurred_local", "2026-09-29T15:30:00");
        // No offline_timed, no clock_offset_ms

        Instant deviceClock = Instant.now().minusSeconds(300);
        ObjectNode batch = batch(Ids.next(), 1, List.of(event));
        batch.put("device_clock", deviceClock.toString());

        ResponseEntity<JsonNode> response = upload(batch);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outcomes(response.getBody())).containsExactly("APPLIED");

        Map<String, Object> outbox = outboxRow(eventId);
        Instant persistedOccurredAt = ((Timestamp) outbox.get("occurred_at")).toInstant();

        // Legacy event retains occurred_at exactly
        assertThat(persistedOccurredAt).isEqualTo(legacyTime);
    }

    @Test
    void retryDoesNotModifyStoredTimestamp() {
        UUID batchId = Ids.next();
        UUID eventId = Ids.next();
        Instant eventTime = Instant.parse("2026-09-29T10:00:00.000Z");

        ObjectNode event = event(1);
        event.put("event_id", eventId.toString());
        event.put("occurred_at", eventTime.toString());
        event.put("offline_timed", true);

        Instant deviceClock1 = Instant.now().minusSeconds(100);
        ObjectNode batch1 = batch(batchId, 1, List.of(event));
        batch1.put("device_clock", deviceClock1.toString());

        ResponseEntity<JsonNode> response1 = upload(batch1);
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.OK);
        Instant storedFirst = ((Timestamp) outboxRow(eventId).get("occurred_at")).toInstant();

        // Resend same batch later with updated batch device clock
        ObjectNode batch2 = batch(batchId, 1, List.of(event));
        batch2.put("device_clock", Instant.now().minusSeconds(200).toString());

        ResponseEntity<JsonNode> response2 = upload(batch2);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Stored timestamp in outbox was NOT modified on replay
        Instant storedSecond = ((Timestamp) outboxRow(eventId).get("occurred_at")).toInstant();
        assertThat(storedSecond).isEqualTo(storedFirst);
    }

    @Test
    void heartbeatFallback_usedWhenBatchDeviceClockAbsent() {
        UUID eventId = Ids.next();
        Instant eventTime = Instant.parse("2026-09-29T10:00:00.000Z");

        // Record a heartbeat with known offset for DEVICE
        superuserJdbc()
                .update(
                        """
                insert into kernel.device_heartbeat (device_id, owner_entity_id, last_seen_at, app_version, clock_offset_ms)
                values (?, ?, now(), '1.0.0', 180000)
                on conflict (device_id) do update set clock_offset_ms = 180000, last_seen_at = now()
                """,
                        DEVICE,
                        ENTITY);

        ObjectNode event = event(1);
        event.put("event_id", eventId.toString());
        event.put("occurred_at", eventTime.toString());
        event.put("offline_timed", true);

        // Batch has NO device_clock
        ObjectNode batch = batch(Ids.next(), 1, List.of(event));
        batch.remove("device_clock");

        ResponseEntity<JsonNode> response = upload(batch);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outcomes(response.getBody())).containsExactly("APPLIED");

        Map<String, Object> outbox = outboxRow(eventId);
        Instant persistedOccurredAt = ((Timestamp) outbox.get("occurred_at")).toInstant();

        // Corrected by exactly 180,000 ms from heartbeat
        assertThat(persistedOccurredAt).isEqualTo(eventTime.plusMillis(180_000));
    }

    @Test
    void offsetResolution_isolatedToAuthenticatedDevice() {
        UUID otherDevice = UUID.fromString("0190a800-0000-7000-8000-000000000399");

        // Heartbeat for other device has offset +999,999 ms
        superuserJdbc()
                .update(
                        """
                insert into kernel.device_heartbeat (device_id, owner_entity_id, last_seen_at, app_version, clock_offset_ms)
                values (?, ?, now(), '1.0.0', 999999)
                on conflict (device_id) do update set clock_offset_ms = 999999, last_seen_at = now()
                """,
                        otherDevice,
                        ENTITY);

        // DEVICE has heartbeat with +45,000 ms
        superuserJdbc()
                .update(
                        """
                insert into kernel.device_heartbeat (device_id, owner_entity_id, last_seen_at, app_version, clock_offset_ms)
                values (?, ?, now(), '1.0.0', 45000)
                on conflict (device_id) do update set clock_offset_ms = 45000, last_seen_at = now()
                """,
                        DEVICE,
                        ENTITY);

        UUID eventId = Ids.next();
        Instant eventTime = Instant.parse("2026-09-29T10:00:00.000Z");
        ObjectNode event = event(1);
        event.put("event_id", eventId.toString());
        event.put("occurred_at", eventTime.toString());
        event.put("offline_timed", true);

        ObjectNode batch = batch(Ids.next(), 1, List.of(event));
        batch.remove("device_clock");

        ResponseEntity<JsonNode> response = upload(batch);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> outbox = outboxRow(eventId);
        Instant persistedOccurredAt = ((Timestamp) outbox.get("occurred_at")).toInstant();

        // Applies DEVICE's offset (+45,000 ms), NOT otherDevice's offset (+999,999 ms)
        assertThat(persistedOccurredAt).isEqualTo(eventTime.plusMillis(45_000));
    }

    @Test
    void inconsistentMetadata_offlineTimedTrueWithNonZeroOffset_safelyPreservesOccurredAt() {
        UUID eventId = Ids.next();
        Instant eventTime = Instant.parse("2026-09-29T10:00:00.000Z");

        ObjectNode event = event(1);
        event.put("event_id", eventId.toString());
        event.put("occurred_at", eventTime.toString());
        event.put("offline_timed", true);
        event.put("clock_offset_ms", 50_000L); // Inconsistent: flagged offline_timed but has non-zero offset

        Instant deviceClock = Instant.now().minusSeconds(100);
        ObjectNode batch = batch(Ids.next(), 1, List.of(event));
        batch.put("device_clock", deviceClock.toString());

        ResponseEntity<JsonNode> response = upload(batch);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outcomes(response.getBody())).containsExactly("APPLIED");

        Map<String, Object> outbox = outboxRow(eventId);
        Instant persistedOccurredAt = ((Timestamp) outbox.get("occurred_at")).toInstant();

        // Safely preserved without guessing or shifting
        assertThat(persistedOccurredAt).isEqualTo(eventTime);
    }

    // ---- Helpers ----

    private Map<String, Object> outboxRow(UUID eventId) {
        return superuserJdbc().queryForMap("select * from kernel.event_outbox where event_id = ?", eventId);
    }

    private Map<String, Object> auditRow(UUID auditId) {
        return superuserJdbc().queryForMap("select * from kernel.audit_event where audit_id = ?", auditId);
    }

    private ObjectNode auditEvent(long seq, String code, String subjectTable, UUID subjectId) {
        ObjectNode event = event(seq, "audit.recorded.v1");
        ObjectNode payload = event.putObject("payload");
        payload.put("event_type_code", code);
        payload.put("subject_table", subjectTable);
        payload.put("subject_id", subjectId.toString());
        return event;
    }
}
