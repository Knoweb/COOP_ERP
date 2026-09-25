package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.SyncAnomaly;
import lk.coopfed.knoweb.kernel.internal.document.BundleHash;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import org.junit.jupiter.api.Test;

/**
 * Doc 32 section 11, "Malformed event: quarantined, cursor advanced, later events applied, ALERT
 * raised", with the other reasons of sections 3.3 and 7 a fact is not accepted for: a document
 * bundle whose content hash does not match (or is missing), an event over the size limit, a
 * payload that carries personal data. None of them blocks what follows (S4).
 */
class QuarantineConformanceIntegrationTest extends SyncIntegrationTest {

    @Test
    void aMalformedEventIsQuarantinedTheCursorAdvancesAndLaterEventsAreApplied() {
        ObjectNode malformed = event(2);
        malformed.remove("event_id");
        ObjectNode unversioned = event(3, "receipt.issued");
        ObjectNode wrongSequence = event(4);
        wrongSequence.put("device_seq", 40);
        ObjectNode noPayload = event(5);
        noPayload.put("payload", "not an object");

        JsonNode ack = upload(
                        Ids.next(), 1, List.of(event(1), malformed, unversioned, wrongSequence, noPayload, event(6)))
                .getBody();

        assertThat(outcomes(ack))
                .containsExactly("APPLIED", "QUARANTINED", "QUARANTINED", "QUARANTINED", "QUARANTINED", "APPLIED");
        assertThat(ack.path("outcomes").get(1).path("reason").asText()).isEqualTo("SCHEMA");
        assertThat(ack.path("last_applied_seq").asLong()).isEqualTo(6);
        assertThat(cursor()).isEqualTo(6);
        assertThat(outboxSequences()).containsExactly(1L, 6L);

        // Kept raw with its reason, never lost (S4).
        List<Map<String, Object>> quarantined = superuserJdbc()
                .queryForList(
                        "select device_seq, reason, raw_event, location_id from kernel.sync_quarantine"
                                + " where device_id = ? order by device_seq",
                        DEVICE);
        assertThat(quarantined)
                .extracting(q -> ((Number) q.get("device_seq")).longValue())
                .containsExactly(2L, 3L, 4L, 5L);
        assertThat(quarantined).extracting(q -> q.get("reason")).containsOnly("SCHEMA");
        assertThat((String) quarantined.get(0).get("raw_event")).contains("\"device_seq\":2");
        assertThat(quarantined.get(0).get("location_id")).isEqualTo(SHOP);

        // ALERT raised, and sync.anomaly.v1 for whoever watches.
        assertThat(kernel.committedAudit())
                .filteredOn(a -> a.eventType().equals("SYNC_EVENT_QUARANTINED"))
                .hasSize(4)
                .allSatisfy(a -> assertThat(a.scope().deviceId()).isEqualTo(DEVICE));
        assertThat(kernel.committedEvents())
                .filteredOn(
                        e -> e instanceof SyncAnomaly anomaly && anomaly.kind().equals("QUARANTINED"))
                .hasSize(4);
    }

    @Test
    void aQuarantinedSequenceIsAnsweredQuarantinedWhenTheBatchIsResent() {
        ObjectNode malformed = event(2);
        malformed.remove("event_id");
        List<ObjectNode> events = List.of(event(1), malformed, event(3));
        upload(Ids.next(), 1, events);

        JsonNode replay = upload(Ids.next(), 1, events).getBody();

        assertThat(outcomes(replay)).containsExactly("DUPLICATE", "QUARANTINED", "DUPLICATE");
        assertThat(replay.path("outcomes").get(1).path("reason").asText()).isEqualTo("SCHEMA");
    }

    @Test
    void aDocumentBundleWithItsContentHashIsAppliedUnderTheDocumentsId() {
        UUID documentId = Ids.next();
        ObjectNode bundle = bundle(1, documentId);

        JsonNode ack = upload(Ids.next(), 1, List.of(bundle)).getBody();

        assertThat(outcomes(ack)).containsExactly("APPLIED");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select aggregate_id from kernel.event_outbox where source = ? and source_seq = 1",
                                UUID.class,
                                DEVICE.toString()))
                .isEqualTo(documentId);
    }

    @Test
    void aBundleWhoseHashDoesNotMatchOrIsMissingIsQuarantined() {
        ObjectNode tampered = bundle(1, Ids.next());
        ((ObjectNode) tampered.path("payload").path("lines").get(0)).put("qty", 5);
        ObjectNode unsigned = bundle(2, Ids.next());
        unsigned.remove("content_hash");
        ObjectNode unreadable = bundle(3, Ids.next());
        ((ObjectNode) unreadable.path("payload").path("document")).put("issued_at", "yesterday");

        JsonNode ack = upload(Ids.next(), 1, List.of(tampered, unsigned, unreadable, event(4)))
                .getBody();

        assertThat(outcomes(ack)).containsExactly("QUARANTINED", "QUARANTINED", "QUARANTINED", "APPLIED");
        assertThat(List.of(
                        ack.path("outcomes").get(0).path("reason").asText(),
                        ack.path("outcomes").get(1).path("reason").asText(),
                        ack.path("outcomes").get(2).path("reason").asText()))
                .containsExactly("HASH", "HASH", "SCHEMA");
        assertThat(cursor()).isEqualTo(4);
    }

    @Test
    void aPayloadCarryingPersonalDataIsQuarantinedNotPublished() {
        ObjectNode leaky = event(1);
        ((ObjectNode) leaky.path("payload")).put("customerPhone", "0771234567");

        JsonNode ack = upload(Ids.next(), 1, List.of(leaky, event(2))).getBody();

        assertThat(outcomes(ack)).containsExactly("QUARANTINED", "APPLIED");
        assertThat(ack.path("outcomes").get(0).path("reason").asText()).isEqualTo("FORBIDDEN_FIELD");
        assertThat(outboxSequences()).containsExactly(2L);
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("SYNC_EVENT_QUARANTINED");
    }

    /** A receipt bundle of doc 32 section 3.1 with its content hash, as the till computes it. */
    ObjectNode bundle(long seq, UUID documentId) {
        ObjectNode event = event(seq, "receipt.issued.v1");
        ObjectNode payload = json.createObjectNode();
        ObjectNode document = payload.putObject("document");
        document.put("document_id", documentId.toString());
        document.put("doc_type_code", "RCT");
        document.put("doc_number", seq);
        document.put("doc_number_display", "M001-S01-T1-RCT-000000" + seq);
        document.put("owner_entity_id", ENTITY.toString());
        document.put("location_id", SHOP.toString());
        document.put("till_position_id", POSITION.toString());
        document.put("device_id", DEVICE.toString());
        document.put("issued_at", Instant.parse("2026-09-25T04:30:00Z").toString());
        document.put("business_date", "2026-09-25");
        document.put("operator_user_id", OPERATOR.toString());
        document.put("currency", "LKR");
        document.put("net_amount", 250.00);
        document.put("tax_amount", 0);
        document.put("gross_amount", 250.00);
        document.put("origin", "OFFLINE");
        document.put("device_seq", seq);
        ArrayNode lines = payload.putArray("lines");
        ObjectNode line = lines.addObject();
        line.put("line_no", 1);
        line.put("sku_id", Ids.next().toString());
        line.put("uom_code", "EA");
        line.put("qty", 2);
        line.put("unit_price", 125.0);
        line.put("line_total", 250.00);
        event.set("payload", payload);
        event.put("content_hash", BundleHash.of(document, lines));
        return event;
    }
}
