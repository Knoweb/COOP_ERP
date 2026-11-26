package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.sync.web.generated.DeviceSyncStatus;
import lk.coopfed.knoweb.kernel.sync.web.generated.QuarantinedEvent;
import lk.coopfed.knoweb.kernel.sync.web.generated.SequenceResetRequest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Integration tests for ADM-01:
 * - Quarantine list & detail APIs (GET /v1/sync/quarantine, GET /v1/sync/quarantine/{id})
 * - Device sync-status API (GET /v1/sync/devices/{device_id}/sync-status)
 * - Permission & device-token guards (only authorized admins, device tokens forbidden)
 */
class SyncQuarantineAndStatusIntegrationTest extends SyncIntegrationTest {

    private static final UUID ADMIN = UUID.fromString("0190a800-0000-7000-8000-000000000521");

    private UUID pendingQuarantineId;
    private UUID resolvedQuarantineId;

    @BeforeEach
    void seedQuarantineAndSyncState() {
        // 1. Upload malformed event at seq 2 -> goes to quarantine
        ObjectNode malformed1 = event(2);
        malformed1.put("occurred_at", "invalid-date-1");
        upload(Ids.next(), 1, List.of(event(1), malformed1, event(3)));

        pendingQuarantineId = superuserJdbc()
                .queryForObject(
                        "select quarantine_id from kernel.sync_quarantine where device_id = ? and device_seq = 2",
                        UUID.class,
                        DEVICE);

        // 2. Upload malformed event at seq 5 -> goes to quarantine, then resolve it
        ObjectNode malformed2 = event(5);
        malformed2.put("occurred_at", "invalid-date-2");
        upload(Ids.next(), 4, List.of(event(4), malformed2, event(6)));

        resolvedQuarantineId = superuserJdbc()
                .queryForObject(
                        "select quarantine_id from kernel.sync_quarantine where device_id = ? and device_seq = 5",
                        UUID.class,
                        DEVICE);

        // Resolve seq 5 as REPAIRED
        superuserJdbc()
                .update(
                        """
                update kernel.sync_quarantine
                set resolution = 'REPAIRED',
                    resolution_reason = 'RESENT_CORRECTED: Manually fixed',
                    resolved_at = now(),
                    resolved_by_user_id = ?
                where quarantine_id = ?
                """,
                        ADMIN,
                        resolvedQuarantineId);

        // 3. Send a heartbeat to record device heartbeat facts
        ObjectNode heartbeat = json.createObjectNode();
        heartbeat.put("device_clock", Instant.now().toString());
        heartbeat.put("battery_pct", 88);
        heartbeat.put("storage_free_mb", 2048);
        heartbeat.put("pending_event_count", 0);
        heartbeat.put("last_acknowledged_seq", 6);
        heartbeat.put("app_version", "2.1.0");
        heartbeat.put("open_session", true);
        heartbeat(heartbeat);

        kernel.reset();
    }

    @Test
    void listQuarantineReturnsQuarantinedEventsWithFilters() {
        HttpHeaders headers = adminHeaders();

        // 1. Unfiltered list
        ResponseEntity<List<QuarantinedEvent>> allResp = http.exchange(
                "/v1/sync/quarantine",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});

        assertThat(allResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<QuarantinedEvent> all = allResp.getBody();
        assertThat(all).isNotNull();
        assertThat(all)
                .extracting(QuarantinedEvent::getQuarantineId)
                .contains(pendingQuarantineId, resolvedQuarantineId);

        // 2. Filter resolved=false (only pending)
        ResponseEntity<List<QuarantinedEvent>> pendingResp = http.exchange(
                "/v1/sync/quarantine?resolved=false",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});

        assertThat(pendingResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<QuarantinedEvent> pendingOnly = pendingResp.getBody();
        assertThat(pendingOnly).isNotNull();
        assertThat(pendingOnly)
                .extracting(QuarantinedEvent::getQuarantineId)
                .contains(pendingQuarantineId)
                .doesNotContain(resolvedQuarantineId);

        // 3. Filter resolved=true (only resolved)
        ResponseEntity<List<QuarantinedEvent>> resolvedResp = http.exchange(
                "/v1/sync/quarantine?resolved=true",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});

        assertThat(resolvedResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<QuarantinedEvent> resolvedOnly = resolvedResp.getBody();
        assertThat(resolvedOnly).isNotNull();
        assertThat(resolvedOnly)
                .extracting(QuarantinedEvent::getQuarantineId)
                .contains(resolvedQuarantineId)
                .doesNotContain(pendingQuarantineId);
        assertThat(resolvedOnly.getFirst().getResolution()).isEqualTo(QuarantinedEvent.ResolutionEnum.REPAIRED);

        // 4. Filter by device_id
        ResponseEntity<List<QuarantinedEvent>> deviceResp = http.exchange(
                "/v1/sync/quarantine?device_id=" + DEVICE,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});

        assertThat(deviceResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deviceResp.getBody())
                .extracting(QuarantinedEvent::getDeviceId)
                .containsOnly(DEVICE);
    }

    @Test
    void getQuarantineReturnsDetailWithRawEventAndHandles404() {
        HttpHeaders headers = adminHeaders();

        // 1. Success on existing
        ResponseEntity<QuarantinedEvent> resp = http.exchange(
                "/v1/sync/quarantine/" + pendingQuarantineId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                QuarantinedEvent.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        QuarantinedEvent detail = resp.getBody();
        assertThat(detail).isNotNull();
        assertThat(detail.getQuarantineId()).isEqualTo(pendingQuarantineId);
        assertThat(detail.getDeviceId()).isEqualTo(DEVICE);
        assertThat(detail.getDeviceSeq()).isEqualTo(2);
        assertThat(detail.getRawEvent()).contains("invalid-date-1");
        assertThat(detail.getResolution()).isNull();

        // 2. 404 on non-existent
        UUID randomId = UUID.randomUUID();
        ResponseEntity<JsonNode> notFound = http.exchange(
                "/v1/sync/quarantine/" + randomId, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getBody().path("code").asText()).isEqualTo("sync.quarantine.not_found");
    }

    @Test
    void getDeviceSyncStatusReturnsCursorHeartbeatAndRecentGaps() {
        HttpHeaders headers = adminHeaders();

        // Reset sequence to document a gap (from seq 7 to seq 10)
        HttpHeaders resetHeaders = adminHeaders();
        resetHeaders.setContentType(MediaType.APPLICATION_JSON);
        resetHeaders.set("Idempotency-Key", UUID.randomUUID().toString());
        SequenceResetRequest resetCmd = new SequenceResetRequest(10L, "OUTBOX_LOST").reasonText("Till storage cleared");
        http.exchange(
                "/v1/sync/devices/" + DEVICE + "/sequence-reset",
                HttpMethod.POST,
                new HttpEntity<>(resetCmd, resetHeaders),
                JsonNode.class);

        // Fetch sync status
        ResponseEntity<DeviceSyncStatus> resp = http.exchange(
                "/v1/sync/devices/" + DEVICE + "/sync-status",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                DeviceSyncStatus.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        DeviceSyncStatus status = resp.getBody();
        assertThat(status).isNotNull();
        assertThat(status.getDeviceId()).isEqualTo(DEVICE);
        assertThat(status.getOwnerEntityId()).isEqualTo(ENTITY);
        assertThat(status.getLastAppliedSeq()).isEqualTo(9); // from reset: new_start_seq - 1
        assertThat(status.getAppVersion()).isEqualTo("2.1.0");
        assertThat(status.getBatteryPct()).isEqualTo(88);
        assertThat(status.getStorageFreeMb()).isEqualTo(2048);
        assertThat(status.getOpenSession()).isTrue();
        assertThat(status.getRecentGaps()).isNotEmpty();
        assertThat(status.getRecentGaps().getFirst().getFromSeq()).isEqualTo(7);
        assertThat(status.getRecentGaps().getFirst().getToSeq()).isEqualTo(9);

        // 404 on non-existent device
        UUID nonExistent = UUID.randomUUID();
        ResponseEntity<JsonNode> notFound = http.exchange(
                "/v1/sync/devices/" + nonExistent + "/sync-status",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class);

        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getBody().path("code").asText()).isEqualTo("sync.device.not_found");
    }

    @Test
    void deviceTokensAreForbiddenOnAdminEndpoints() {
        HttpHeaders device = TestIdentityProvider.deviceHeaders(DEVICE);

        // 1. GET /v1/sync/quarantine
        ResponseEntity<JsonNode> qList =
                http.exchange("/v1/sync/quarantine", HttpMethod.GET, new HttpEntity<>(device), JsonNode.class);
        assertThat(qList.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(qList.getBody().path("code").asText()).isEqualTo("sync.device_token_not_allowed");

        // 2. GET /v1/sync/devices/{id}/sync-status
        ResponseEntity<JsonNode> status = http.exchange(
                "/v1/sync/devices/" + DEVICE + "/sync-status",
                HttpMethod.GET,
                new HttpEntity<>(device),
                JsonNode.class);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status.getBody().path("code").asText()).isEqualTo("sync.device_token_not_allowed");
    }

    @Test
    void otherEntityCannotSeeQuarantineOrStatus() {
        HttpHeaders otherEntity = TestIdentityProvider.entityWideHeaders(ADMIN, OTHER_ENTITY);

        // 1. List quarantine for DEVICE belonging to ENTITY returns empty under other entity's RLS
        ResponseEntity<List<QuarantinedEvent>> qResp = http.exchange(
                "/v1/sync/quarantine?device_id=" + DEVICE,
                HttpMethod.GET,
                new HttpEntity<>(otherEntity),
                new ParameterizedTypeReference<>() {});
        assertThat(qResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(qResp.getBody()).isEmpty();

        // 2. Direct quarantine lookup for another entity's quarantine returns 404
        ResponseEntity<JsonNode> detail = http.exchange(
                "/v1/sync/quarantine/" + pendingQuarantineId,
                HttpMethod.GET,
                new HttpEntity<>(otherEntity),
                JsonNode.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(detail.getBody().path("code").asText()).isEqualTo("sync.quarantine.not_found");

        // 3. Device sync status for another entity's device returns 404
        ResponseEntity<JsonNode> sResp = http.exchange(
                "/v1/sync/devices/" + DEVICE + "/sync-status",
                HttpMethod.GET,
                new HttpEntity<>(otherEntity),
                JsonNode.class);
        assertThat(sResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(sResp.getBody().path("code").asText()).isEqualTo("sync.device.not_found");
    }

    private HttpHeaders adminHeaders() {
        return TestIdentityProvider.entityWideHeaders(ADMIN, ENTITY);
    }
}
