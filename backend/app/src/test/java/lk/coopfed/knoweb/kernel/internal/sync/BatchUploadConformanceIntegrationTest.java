package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.SyncAnomaly;
import lk.coopfed.knoweb.kernel.api.SyncBatchReceived;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Doc 32 section 11, the cases of channel 1 that are about the cursor: "Lost acknowledgement"
 * (the same batch resent; every event applied once; cursor correct) and "Gap" (a batch above the
 * cursor rejected with expected_seq; the till recovers from retained rows), with the replay rules
 * of section 3.3 and the anomalies of section 7 around them. Every request is a till's: a device
 * token, JSON or gzip, against the running application.
 */
class BatchUploadConformanceIntegrationTest extends SyncIntegrationTest {

    @Test
    void aBatchIsAppliedInOrderAndHandedToTheEventFramework() {
        ResponseEntity<JsonNode> response = upload(Ids.next(), 1, events(1, 3));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode ack = response.getBody();
        assertThat(ack.path("last_applied_seq").asLong()).isEqualTo(3);
        assertThat(outcomes(ack)).containsExactly("APPLIED", "APPLIED", "APPLIED");
        assertThat(ack.path("server_time").asText()).isNotBlank();
        assertThat(ack.path("clock_offset_ms").isNumber()).isTrue();
        assertThat(ack.path("snapshot_version").asLong()).isZero();
        assertThat(cursor()).isEqualTo(3);
        // In the outbox with the device as source and its sequence: the module consumers get them
        // through the relay in that order (doc 19 section 6.3).
        assertThat(outboxSequences()).containsExactly(1L, 2L, 3L);
        assertThat(superuserJdbc()
                        .queryForList(
                                "select distinct actor_user_id from kernel.event_outbox where source = ?",
                                UUID.class,
                                DEVICE.toString()))
                .containsExactly(OPERATOR);
        assertThat(kernel.committedEvents()).hasAtLeastOneElementOfType(SyncBatchReceived.class);
    }

    @Test
    void lostAcknowledgementTheSameBatchResentIsAppliedOnceAndAnsweredTheSame() {
        UUID batchId = Ids.next();
        ObjectNode batch = batch(batchId, 1, events(1, 4));
        JsonNode first = upload(batch).getBody();
        kernel.reset();

        ResponseEntity<JsonNode> resent = upload(batch);

        assertThat(resent.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resent.getBody().path("last_applied_seq").asLong()).isEqualTo(4);
        assertThat(resent.getBody().path("outcomes")).isEqualTo(first.path("outcomes"));
        assertThat(cursor()).isEqualTo(4);
        assertThat(outboxSequences()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void aResendUnderANewBatchIdIsAnsweredFromStateAsDuplicates() {
        List<ObjectNode> events = events(1, 3);
        upload(Ids.next(), 1, events);

        JsonNode replay = upload(Ids.next(), 1, events).getBody();

        assertThat(outcomes(replay)).containsExactly("DUPLICATE", "DUPLICATE", "DUPLICATE");
        assertThat(replay.path("outcomes").get(0).path("event_id").asText())
                .isEqualTo(events.get(0).path("event_id").asText());
        assertThat(outboxSequences()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void aBatchOverlappingTheCursorAppliesOnlyWhatIsNew() {
        List<ObjectNode> events = events(1, 6);
        upload(Ids.next(), 1, events.subList(0, 3));

        JsonNode ack = upload(Ids.next(), 2, events.subList(1, 6)).getBody();

        assertThat(outcomes(ack)).containsExactly("DUPLICATE", "DUPLICATE", "APPLIED", "APPLIED", "APPLIED");
        assertThat(ack.path("last_applied_seq").asLong()).isEqualTo(6);
        assertThat(outboxSequences()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    @Test
    void gapABatchAboveTheCursorIsRefusedWithTheExpectedSequenceAndTheTillRecovers() {
        List<ObjectNode> retained = events(1, 8);
        upload(Ids.next(), 1, retained.subList(0, 3));

        ResponseEntity<JsonNode> gap = upload(Ids.next(), 6, retained.subList(5, 8));

        assertThat(gap.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(gap.getBody().path("code").asText()).isEqualTo("sync.sequence_gap");
        assertThat(gap.getBody().path("params").path("expected_seq").asLong()).isEqualTo(4);
        assertThat(cursor()).isEqualTo(3);

        // The till never purged what was not acknowledged, and resends from expected_seq.
        ResponseEntity<JsonNode> recovered = upload(Ids.next(), 4, retained.subList(3, 8));

        assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recovered.getBody().path("last_applied_seq").asLong()).isEqualTo(8);
        assertThat(outboxSequences()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
    }

    @Test
    void repeatedGapsFromOneDeviceRaiseASyncAnomalyOnce() {
        upload(Ids.next(), 1, events(1, 1));

        for (int i = 0; i < 4; i++) {
            assertThat(upload(Ids.next(), 5, events(5, 5)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        assertThat(kernel.committedAudit())
                .filteredOn(a -> a.eventType().equals("SYNC_ANOMALY"))
                .hasSize(1);
        assertThat(kernel.committedEvents())
                .filteredOn(
                        e -> e instanceof SyncAnomaly anomaly && anomaly.kind().equals("REPEATED_GAP"))
                .hasSize(1);
    }

    @Test
    void anEventIdSeenAtAnotherSequenceIsQuarantinedAndTheCursorMovesOn() {
        ObjectNode original = event(1);
        upload(Ids.next(), 1, List.of(original));
        ObjectNode copy = event(2);
        copy.put("event_id", original.path("event_id").asText());

        JsonNode ack = upload(Ids.next(), 2, List.of(copy, event(3))).getBody();

        assertThat(outcomes(ack)).containsExactly("QUARANTINED", "APPLIED");
        assertThat(ack.path("outcomes").get(0).path("reason").asText()).isEqualTo("DUPLICATE_ID");
        assertThat(cursor()).isEqualTo(3);
        assertThat(outboxSequences()).containsExactly(1L, 3L);
    }

    @Test
    void theShapeOfABatchIsChecked() {
        ObjectNode inconsistent = batch(Ids.next(), 1, events(1, 3));
        inconsistent.put("last_seq", 5);
        ResponseEntity<JsonNode> refused = upload(inconsistent);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().path("code").asText()).isEqualTo("sync.batch_inconsistent");

        ResponseEntity<JsonNode> tooMany = upload(Ids.next(), 1, events(1, 501));
        assertThat(tooMany.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(tooMany.getBody().path("code").asText()).isEqualTo("sync.batch_too_large");

        ObjectNode noBatchId = batch(Ids.next(), 1, events(1, 1));
        noBatchId.remove("batch_id");
        assertThat(upload(noBatchId).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(cursor()).isZero();
    }

    @Test
    void aGzipBatchIsInflatedAndApplied() throws IOException {
        ObjectNode batch = batch(Ids.next(), 1, events(1, 2));
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(json.writeValueAsBytes(batch));
        }
        HttpHeaders headers = TestIdentityProvider.deviceHeaders(DEVICE);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Content-Encoding", "gzip");
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/sync/devices/" + DEVICE + "/batches",
                HttpMethod.POST,
                new HttpEntity<>(compressed.toByteArray(), headers),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outcomes(response.getBody())).containsExactly("APPLIED", "APPLIED");
    }

    @Test
    void aBatchOfADeviceNeverEnrolledForSyncIsRefused() {
        superuserJdbc().update("delete from kernel.device_sync_cursor where device_id = ?", DEVICE);

        ResponseEntity<JsonNode> refused = upload(Ids.next(), 1, events(1, 1));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().path("code").asText()).isEqualTo("sync.device_not_enrolled");
    }
}
