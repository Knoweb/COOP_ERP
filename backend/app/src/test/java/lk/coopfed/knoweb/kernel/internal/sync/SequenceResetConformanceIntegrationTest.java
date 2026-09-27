package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.SyncSequenceReset;
import lk.coopfed.knoweb.kernel.sync.web.generated.SequenceGap;
import lk.coopfed.knoweb.kernel.sync.web.generated.SequenceResetRequest;
import lk.coopfed.knoweb.kernel.sync.web.generated.SyncAck;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import lk.coopfed.knoweb.testsupport.TillSimulator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Doc 32 section 7, "till outbox lost", and section 8, "sequence reset (recovery)": a till whose
 * unsent rows are gone is answered 409 sync.sequence_gap on every batch until an administrator
 * moves its cursor past the lost numbers with a reason code; central records the gap range, the
 * cursor moves to the device's new start, an ALERT is raised, and the till uploads again. Run with
 * the {@link TillSimulator} for the till and the generated request and answer types for the
 * administrator's call (the contract test of the operation).
 */
class SequenceResetConformanceIntegrationTest extends SyncIntegrationTest {

    private static final UUID ADMIN = UUID.fromString("0190a800-0000-7000-8000-000000000511");

    @Autowired
    TillSigner signer;

    private TillSimulator till() {
        return new TillSimulator(http, json, DEVICE, SHOP, signer.publicKeyBase64());
    }

    @Test
    void aTillThatLostItsOutboxIsMovedOnByTheAdministratorAndTheGapIsDocumented() {
        TillSimulator till = till();
        till.recordSales(2);
        till.uploadOnce(500);
        till.recordSales(3); // sequences 3, 4, 5: never sent
        assertThat(till.loseUnsentEvents()).isEqualTo(3);
        till.recordSales(2); // sequences 6 and 7, after the loss

        // The till cannot produce expected_seq: every batch is a gap.
        assertThatThrownBy(() -> till.uploadOnce(500)).isInstanceOfSatisfying(TillSimulator.Refused.class, refused -> {
            assertThat(refused.status).isEqualTo(409);
            assertThat(refused.problem.path("code").asText()).isEqualTo("sync.sequence_gap");
            assertThat(refused.problem.path("params").path("expected_seq").asLong())
                    .isEqualTo(3);
        });
        kernel.reset();

        ResponseEntity<SequenceGap> reset = reset(6, "OUTBOX_LOST", "Local database corrupted", adminHeaders());

        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        SequenceGap gap = reset.getBody();
        assertThat(gap.getDeviceId()).isEqualTo(DEVICE);
        assertThat(gap.getFromSeq()).isEqualTo(3);
        assertThat(gap.getToSeq()).isEqualTo(5);
        assertThat(cursor()).isEqualTo(5);
        Map<String, Object> row =
                superuserJdbc().queryForMap("select * from kernel.sync_sequence_gap where gap_id = ?", gap.getGapId());
        assertThat(row)
                .containsEntry("from_seq", 3L)
                .containsEntry("to_seq", 5L)
                .containsEntry("reason_code", "OUTBOX_LOST")
                .containsEntry("recorded_by_user_id", ADMIN)
                .containsEntry("owner_entity_id", ENTITY);
        assertThat(kernel.committedAudit())
                .filteredOn(a -> a.eventType().equals("SYNC_SEQUENCE_RESET"))
                .singleElement()
                .satisfies(a -> assertThat(a.reason()).isEqualTo("OUTBOX_LOST: Local database corrupted"));
        assertThat(kernel.committedEvents())
                .filteredOn(e -> e instanceof SyncSequenceReset)
                .singleElement()
                .isEqualTo(new SyncSequenceReset(gap.getGapId(), DEVICE, 3, 5, "OUTBOX_LOST"));

        // The till uploads again from its new start, and nothing of the lost range is applied.
        SyncAck ack = till.uploadOnce(500);
        assertThat(ack.getLastAppliedSeq()).isEqualTo(7);
        assertThat(till.pending()).isZero();
        assertThat(outboxSequences()).containsExactly(1L, 2L, 6L, 7L);
    }

    @Test
    void aRetryWithTheSameKeyIsAnsweredWithTheGapItRecorded() {
        advanceCursorTo(4);
        HttpHeaders headers = adminHeaders();
        String key = UUID.randomUUID().toString();

        SequenceGap first = reset(10, "OUTBOX_LOST", null, headers, key).getBody();
        kernel.reset();
        SequenceGap again = reset(10, "OUTBOX_LOST", null, headers, key).getBody();

        assertThat(again.getGapId()).isEqualTo(first.getGapId());
        assertThat(cursor()).isEqualTo(9);
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.sync_sequence_gap where device_id = ?",
                                Long.class,
                                DEVICE))
                .isEqualTo(1);
    }

    @Test
    void theCursorNeverGoesBack_aNewStartAtOrBelowTheExpectedSequenceIsRefused() {
        advanceCursorTo(4);

        assertRefused(resetJson(5, adminHeaders()), HttpStatus.UNPROCESSABLE_ENTITY, "sync.sequence_reset.not_forward");
        assertRefused(resetJson(3, adminHeaders()), HttpStatus.UNPROCESSABLE_ENTITY, "sync.sequence_reset.not_forward");

        assertThat(cursor()).isEqualTo(4);
        assertNothingCommitted();
    }

    @Test
    void aDeviceOfAnotherEntityOrNeverEnrolledIsRefused() {
        assertRefused(
                resetJson(10, TestIdentityProvider.entityWideHeaders(ADMIN, OTHER_ENTITY)),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "sync.sequence_reset.device_not_enrolled");

        superuserJdbc().update("delete from kernel.device_sync_cursor where device_id = ?", DEVICE);
        assertRefused(
                resetJson(10, adminHeaders()),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "sync.sequence_reset.device_not_enrolled");
        assertNothingCommitted();
    }

    @Test
    void notWhileABatchOfTheDeviceIsBeingIngested() {
        UUID inFlight = Ids.next();
        superuserJdbc()
                .update(
                        "update kernel.device_sync_cursor set in_flight_batch_id = ?, in_flight_since = ? where device_id = ?",
                        inFlight,
                        Timestamp.from(Instant.now()),
                        DEVICE);

        ResponseEntity<JsonNode> refused = resetJson(10, adminHeaders());

        assertRefused(refused, HttpStatus.CONFLICT, "sync.batch_in_flight");
        assertThat(refused.getBody().path("params").path("in_flight_batch_id").asText())
                .isEqualTo(inFlight.toString());
        assertThat(cursor()).isZero();
        assertNothingCommitted();
    }

    @Test
    void onlyAnAdministratorOfTheOwnClass_neitherTheDeviceNorAViewer() {
        // The device's own token opens nothing but the device operations.
        assertRefused(
                resetJson(10, TestIdentityProvider.deviceHeaders(DEVICE)),
                HttpStatus.FORBIDDEN,
                "sync.device_token_not_allowed");
        // A federation viewer holds no OWN scope of the device's entity.
        HttpHeaders viewer = new HttpHeaders();
        viewer.setBearerAuth(TestIdentityProvider.entityWideToken(ADMIN, ENTITY, "FEDERATION_VIEW"));
        assertRefused(resetJson(10, viewer), HttpStatus.FORBIDDEN, "permission.denied");

        assertThat(cursor()).isZero();
        assertNothingCommitted();
    }

    // ---- the administrator's call ----

    private HttpHeaders adminHeaders() {
        return TestIdentityProvider.entityWideHeaders(ADMIN, ENTITY);
    }

    /** Through the generated request and answer types: the contract test of resetDeviceSequence. */
    private ResponseEntity<SequenceGap> reset(
            long newStart, String reasonCode, String reasonText, HttpHeaders headers) {
        return reset(
                newStart, reasonCode, reasonText, headers, UUID.randomUUID().toString());
    }

    private ResponseEntity<SequenceGap> reset(
            long newStart, String reasonCode, String reasonText, HttpHeaders headers, String key) {
        HttpHeaders copy = new HttpHeaders();
        copy.addAll(headers);
        copy.setContentType(MediaType.APPLICATION_JSON);
        copy.set("Idempotency-Key", key);
        SequenceResetRequest body = new SequenceResetRequest(newStart, reasonCode).reasonText(reasonText);
        return http.exchange(
                "/v1/sync/devices/" + DEVICE + "/sequence-reset",
                HttpMethod.POST,
                new HttpEntity<>(body, copy),
                SequenceGap.class);
    }

    private ResponseEntity<JsonNode> resetJson(long newStart, HttpHeaders headers) {
        ObjectNode body = json.createObjectNode();
        body.put("new_start_seq", newStart);
        body.put("reason_code", "OUTBOX_LOST");
        return post("/v1/sync/devices/" + DEVICE + "/sequence-reset", body, headers);
    }

    private void advanceCursorTo(long seq) {
        List<ObjectNode> events = events(1, seq);
        assertThat(upload(Ids.next(), 1, events).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cursor()).isEqualTo(seq);
        kernel.reset();
    }

    private void assertNothingCommitted() {
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .doesNotContain("SYNC_SEQUENCE_RESET");
        assertThat(kernel.committedEvents()).noneMatch(e -> e instanceof SyncSequenceReset);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.sync_sequence_gap where device_id = ?",
                                Long.class,
                                DEVICE))
                .isZero();
    }

    private static void assertRefused(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().path("code").asText()).isEqualTo(code);
    }
}
