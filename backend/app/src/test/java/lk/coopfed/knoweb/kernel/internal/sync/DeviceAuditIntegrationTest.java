package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The till's own audit rows (doc 32 section 3.1: the {@code audit.*} family, "applied by the
 * kernel audit store, insert only"; doc 18 part D): each lands in {@code kernel.audit_event} as the
 * till recorded it, in the ingestion transaction, with the till's id, time, operator, device and
 * sequence. A row that cannot be read is quarantined like any malformed event (S4), and the
 * events after it are applied.
 */
class DeviceAuditIntegrationTest extends SyncIntegrationTest {

    @Test
    void aTillsAuditRowIsWrittenToTheAuditLogAsTheTillRecordedIt() {
        UUID document = Ids.next();
        UUID witness = Ids.next();
        Instant occurred = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        ObjectNode row = auditEvent(1, "DOCUMENT_ISSUED", "document", document);
        row.put("occurred_at", occurred.toString());
        row.put("occurred_local", "2026-09-27T09:15:00");
        ObjectNode payload = (ObjectNode) row.get("payload");
        payload.put("document_id", document.toString());
        payload.put("reason_code", "SALE");
        payload.put("witness_user_id", witness.toString());
        payload.putObject("after_state").put("status", "ISSUED");

        ResponseEntity<JsonNode> ack = upload(Ids.next(), 1, List.of(row, event(2)));

        assertThat(ack.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outcomes(ack.getBody())).containsExactly("APPLIED", "APPLIED");
        Map<String, Object> audit = auditRow(UUID.fromString(row.get("event_id").asText()));
        assertThat(audit)
                .containsEntry("event_type_code", "DOCUMENT_ISSUED")
                .containsEntry("owner_entity_id", ENTITY)
                .containsEntry("location_id", SHOP)
                .containsEntry("actor_user_id", OPERATOR)
                .containsEntry("device_id", DEVICE)
                .containsEntry("till_position_id", POSITION)
                .containsEntry("device_seq", 1L)
                .containsEntry("subject_table", "document")
                .containsEntry("subject_id", document)
                .containsEntry("document_id", document)
                .containsEntry("reason_code", "SALE")
                .containsEntry("witness_user_id", witness);
        assertThat(((Timestamp) audit.get("occurred_at")).toInstant()).isEqualTo(occurred);
        assertThat(String.valueOf(audit.get("after_state"))).contains("ISSUED");
        // The event is in the outbox as well, like every fact a till sends.
        assertThat(outboxSequences()).containsExactly(1L, 2L);
    }

    @Test
    void anAuditRowThatCannotBeReadIsQuarantinedAndWhatFollowsIsApplied() {
        ObjectNode unknownType = auditEvent(1, "NOT_IN_THE_CATALOGUE", "document", Ids.next());
        // In the catalogue, but only central may record it.
        ObjectNode centralOnly = auditEvent(2, "SYNC_ANOMALY", "device", DEVICE);
        ObjectNode noSubject = auditEvent(3, "DOCUMENT_ISSUED", "document", Ids.next());
        ((ObjectNode) noSubject.get("payload")).remove("subject_id");
        ObjectNode stateNotAnObject = auditEvent(4, "DOCUMENT_ISSUED", "document", Ids.next());
        ((ObjectNode) stateNotAnObject.get("payload")).put("before_state", "DRAFT");

        ResponseEntity<JsonNode> ack =
                upload(Ids.next(), 1, List.of(unknownType, centralOnly, noSubject, stateNotAnObject, event(5)));

        assertThat(ack.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outcomes(ack.getBody()))
                .containsExactly("QUARANTINED", "QUARANTINED", "QUARANTINED", "QUARANTINED", "APPLIED");
        assertThat(cursor()).isEqualTo(5);
        assertThat(superuserJdbc()
                        .queryForList(
                                "select reason from kernel.sync_quarantine where device_id = ? order by device_seq",
                                String.class,
                                DEVICE))
                .containsExactly("SCHEMA", "SCHEMA", "SCHEMA", "SCHEMA");
        for (ObjectNode refused : List.of(unknownType, centralOnly, noSubject, stateNotAnObject)) {
            assertThat(superuserJdbc()
                            .queryForObject(
                                    "select count(*) from kernel.audit_event where audit_id = ?",
                                    Long.class,
                                    UUID.fromString(refused.get("event_id").asText())))
                    .isZero();
        }
        assertThat(outboxSequences()).containsExactly(5L);
    }

    /** An audit row of the till, in the envelope of doc 19 section 6.1. */
    private ObjectNode auditEvent(long seq, String code, String subjectTable, UUID subjectId) {
        ObjectNode event = event(seq, "audit.recorded.v1");
        ObjectNode payload = event.putObject("payload");
        payload.put("event_type_code", code);
        payload.put("subject_table", subjectTable);
        payload.put("subject_id", subjectId.toString());
        return event;
    }

    private Map<String, Object> auditRow(UUID auditId) {
        return superuserJdbc().queryForMap("select * from kernel.audit_event where audit_id = ?", auditId);
    }
}
