package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.SyncQuarantineResolved;
import lk.coopfed.knoweb.kernel.sync.web.generated.QuarantineResolution;
import lk.coopfed.knoweb.kernel.sync.web.generated.ResolvedQuarantine;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Wave 2, CR-32-1 item 2 (decided 6 October 2026: docs/progress/deviations/2026-10-06-wave2-till-facts-at-the-gateway.md
 * (5)): a quarantined till event is resolved, never purged by time. An administrator of the
 * device's entity marks it REPAIRED or DISCARDED with a reason, once (audited, published); a
 * second resolution is refused, by the handler and by the table's trigger; the nightly job nulls
 * the raw event of a row resolved more than the retention ago and nothing else; the row stays.
 */
class QuarantineResolutionIntegrationTest extends SyncIntegrationTest {

    private static final UUID ADMIN = UUID.fromString("0190a800-0000-7000-8000-000000000521");

    @Autowired
    QuarantineRawRetentionJob retention;

    private UUID quarantined;

    @BeforeEach
    void aMalformedEventQuarantined() {
        ObjectNode malformed = event(2);
        malformed.put("occurred_at", "not a time");
        upload(Ids.next(), 1, List.of(event(1), malformed, event(3)));
        quarantined = superuserJdbc()
                .queryForObject(
                        "select quarantine_id from kernel.sync_quarantine where device_id = ? and device_seq = 2",
                        UUID.class,
                        DEVICE);
        kernel.reset();
    }

    @Test
    void anAdministratorResolvesItOnceWithAReason() {
        ResponseEntity<ResolvedQuarantine> answer = resolve(
                quarantined, QuarantineResolution.ResolutionEnum.DISCARDED, "TEST_EVENT", "sent by the trial till");

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResolvedQuarantine body = answer.getBody();
        assertThat(body.getQuarantineId()).isEqualTo(quarantined);
        assertThat(body.getDeviceId()).isEqualTo(DEVICE);
        assertThat(body.getDeviceSeq()).isEqualTo(2);
        assertThat(body.getReason()).isEqualTo("SCHEMA");
        assertThat(body.getResolution()).isEqualTo(ResolvedQuarantine.ResolutionEnum.DISCARDED);

        Map<String, Object> row = row();
        assertThat(row)
                .containsEntry("resolution", "DISCARDED")
                .containsEntry("resolution_reason", "TEST_EVENT: sent by the trial till")
                .containsEntry("resolved_by_user_id", ADMIN);
        assertThat(row.get("resolved_at")).isNotNull();
        assertThat(row.get("raw_event")).as("kept until the retention").isNotNull();

        assertThat(kernel.committedAudit())
                .filteredOn(a -> a.eventType().equals("SYNC_QUARANTINE_RESOLVED"))
                .singleElement()
                .satisfies(a -> assertThat(a.reason()).isEqualTo("TEST_EVENT: sent by the trial till"));
        assertThat(kernel.committedEvents())
                .filteredOn(e -> e instanceof SyncQuarantineResolved)
                .singleElement()
                .isEqualTo(new SyncQuarantineResolved(quarantined, DEVICE, 2, "SCHEMA", "DISCARDED", "TEST_EVENT"));
    }

    @Test
    void aSecondResolutionIsRefusedAndChangesNothing() {
        resolve(quarantined, QuarantineResolution.ResolutionEnum.REPAIRED, "RESENT_CORRECTED", null);
        kernel.reset();

        ResponseEntity<JsonNode> again = resolveJson(quarantined, "DISCARDED", adminHeaders());

        assertRefused(again, HttpStatus.UNPROCESSABLE_ENTITY, "sync.quarantine.already_resolved");
        assertThat(row())
                .containsEntry("resolution", "REPAIRED")
                .containsEntry("resolution_reason", "RESENT_CORRECTED");
        assertNothingCommitted();
    }

    @Test
    void theTableItselfLetsARowBeResolvedOnceAndKeepsWhatItRecords() {
        resolve(quarantined, QuarantineResolution.ResolutionEnum.REPAIRED, "RESENT_CORRECTED", null);

        // Even past the application (the migrator, the superuser): the trigger of kernel V0084.
        assertThatThrownBy(() -> superuserJdbc()
                        .update(
                                "update kernel.sync_quarantine set resolution = 'DISCARDED' where quarantine_id = ?",
                                quarantined))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("sync_quarantine.already_resolved");
        assertThatThrownBy(() -> superuserJdbc()
                        .update(
                                "update kernel.sync_quarantine set reason = 'HASH' where quarantine_id = ?",
                                quarantined))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("sync_quarantine.identity_immutable");
        assertThatThrownBy(() -> superuserJdbc()
                        .update(
                                "update kernel.sync_quarantine set raw_event = '{}' where quarantine_id = ?",
                                quarantined))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("sync_quarantine.raw_event_immutable");
    }

    @Test
    void theRawEventOfAnUnresolvedRowCannotBeDropped() {
        assertThatThrownBy(() -> superuserJdbc()
                        .update(
                                "update kernel.sync_quarantine set raw_event = null where quarantine_id = ?",
                                quarantined))
                .isInstanceOf(DataAccessException.class);
        assertThat(row().get("raw_event")).isNotNull();
    }

    @Test
    void theNightlyJobDropsTheRawEventOfRowsResolvedLongEnoughAgoAndKeepsTheRow() {
        // A second quarantined row, resolved now: within the retention.
        ObjectNode another = event(4);
        another.remove("event_id");
        upload(Ids.next(), 4, List.of(another));
        UUID recent = superuserJdbc()
                .queryForObject(
                        "select quarantine_id from kernel.sync_quarantine where device_id = ? and device_seq = 4",
                        UUID.class,
                        DEVICE);
        // A third, never resolved.
        ObjectNode unresolved = event(5);
        unresolved.remove("event_id");
        upload(Ids.next(), 5, List.of(unresolved));
        resolve(quarantined, QuarantineResolution.ResolutionEnum.DISCARDED, "TEST_EVENT", null);
        resolve(recent, QuarantineResolution.ResolutionEnum.DISCARDED, "TEST_EVENT", null);
        // The first was resolved 31 days ago (the trigger lets the superuser move nothing but the
        // raw event of a resolved row, so the test moves the time it compares with instead).
        Instant cutOff = Instant.now().plus(1, ChronoUnit.MINUTES);
        superuserJdbc().execute("alter table kernel.sync_quarantine disable trigger trg_sync_quarantine_transition");
        try {
            superuserJdbc()
                    .update(
                            "update kernel.sync_quarantine set resolved_at = ? where quarantine_id = ?",
                            Timestamp.from(Instant.now().minus(31, ChronoUnit.DAYS)),
                            quarantined);
        } finally {
            superuserJdbc().execute("alter table kernel.sync_quarantine enable trigger trg_sync_quarantine_transition");
        }

        int dropped = retention.dropRaw();

        assertThat(dropped).isEqualTo(1);
        assertThat(row().get("raw_event")).isNull();
        assertThat(row()).containsEntry("resolution", "DISCARDED").containsEntry("reason", "SCHEMA");
        assertThat(superuserJdbc()
                        .queryForList(
                                "select device_seq from kernel.sync_quarantine where device_id = ? and raw_event is not null"
                                        + " order by device_seq",
                                Long.class,
                                DEVICE))
                .as("the row resolved within the retention and the unresolved one keep theirs")
                .containsExactly(4L, 5L);
        // A cut-off in the future is refused by the function: only the past is dropped.
        assertThatThrownBy(() -> retention.dropRawResolvedBefore(cutOff.plus(1, ChronoUnit.DAYS)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void aRowOfAnotherEntityIsNotFoundAndNothingIsCommitted() {
        ResponseEntity<JsonNode> elsewhere =
                resolveJson(quarantined, "DISCARDED", TestIdentityProvider.entityWideHeaders(ADMIN, OTHER_ENTITY));
        assertRefused(elsewhere, HttpStatus.NOT_FOUND, "sync.quarantine.not_found");
        assertRefused(
                resolveJson(Ids.next(), "DISCARDED", adminHeaders()),
                HttpStatus.NOT_FOUND,
                "sync.quarantine.not_found");
        assertThat(row().get("resolved_at")).isNull();
        assertNothingCommitted();
    }

    @Test
    void onlyAnAdministratorOfTheOwnClass_neitherTheDeviceNorAViewer() {
        assertRefused(
                resolveJson(quarantined, "DISCARDED", TestIdentityProvider.deviceHeaders(DEVICE)),
                HttpStatus.FORBIDDEN,
                "sync.device_token_not_allowed");
        HttpHeaders viewer = new HttpHeaders();
        viewer.setBearerAuth(TestIdentityProvider.entityWideToken(ADMIN, ENTITY, "FEDERATION_VIEW"));
        assertRefused(resolveJson(quarantined, "DISCARDED", viewer), HttpStatus.FORBIDDEN, "permission.denied");
        assertThat(row().get("resolved_at")).isNull();
        assertNothingCommitted();
    }

    @Test
    void aResolutionTheSliceDoesNotNameIsARequestProblem() {
        assertThat(resolveJson(quarantined, "FORGOTTEN", adminHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertNothingCommitted();
    }

    // ---- the administrator's call ----

    private HttpHeaders adminHeaders() {
        return TestIdentityProvider.entityWideHeaders(ADMIN, ENTITY);
    }

    /** Through the generated request and answer types: the contract test of resolveQuarantine. */
    private ResponseEntity<ResolvedQuarantine> resolve(
            UUID id, QuarantineResolution.ResolutionEnum resolution, String reasonCode, String reasonText) {
        HttpHeaders headers = adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        QuarantineResolution body = new QuarantineResolution(resolution, reasonCode).reasonText(reasonText);
        return http.exchange(
                "/v1/sync/quarantine/" + id + "/resolve",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                ResolvedQuarantine.class);
    }

    private ResponseEntity<JsonNode> resolveJson(UUID id, String resolution, HttpHeaders headers) {
        ObjectNode body = json.createObjectNode();
        body.put("resolution", resolution);
        body.put("reason_code", "TEST_EVENT");
        return post("/v1/sync/quarantine/" + id + "/resolve", body, headers);
    }

    private Map<String, Object> row() {
        return superuserJdbc().queryForMap("select * from kernel.sync_quarantine where quarantine_id = ?", quarantined);
    }

    private void assertNothingCommitted() {
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .doesNotContain("SYNC_QUARANTINE_RESOLVED");
        assertThat(kernel.committedEvents()).noneMatch(e -> e instanceof SyncQuarantineResolved);
    }

    private static void assertRefused(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().path("code").asText()).isEqualTo(code);
    }
}
