package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DeviceCredentials;
import lk.coopfed.knoweb.kernel.api.DeviceSyncEnrolled;
import lk.coopfed.knoweb.kernel.api.EnrolmentCodeIssued;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Device enrolment (doc 32 section 8): an administrator issues a one-time code, the till presents
 * it once, without a token, and receives its credential, its first sequence, the series it holds,
 * the snapshot pointer and the signing key. The provider is replaced by a double here (its own
 * test, KeycloakDeviceCredentialsIntegrationTest, runs against the real one): what is proved is
 * what the kernel decides.
 */
class EnrolmentIntegrationTest extends SyncIntegrationTest {

    private static final UUID ADMIN = UUID.fromString("0190a800-0000-7000-8000-000000000501");
    private static final UUID POSITION_SERIES = UUID.fromString("0190a800-0000-7000-8000-000000000601");
    private static final UUID LOCATION_SERIES = UUID.fromString("0190a800-0000-7000-8000-000000000602");

    @MockitoBean
    DeviceCredentials credentials;

    @BeforeEach
    void theProviderAndTheSeries() {
        when(credentials.issue(eq(DEVICE), eq(ENTITY)))
                .thenReturn(new DeviceCredentials.Credential(
                        "device-" + DEVICE, "the-secret", "http://provider.test/realms/coop/token"));
        // The device enrols afresh: no cursor yet.
        JdbcTemplate db = superuserJdbc();
        db.update("delete from kernel.device_sync_cursor where device_id = ?", DEVICE);
        db.update(
                """
                insert into kernel.numbering_series (series_id, doc_type_code, series_scope, owner_entity_id, location_id,
                                                     till_position_id, prefix, next_number, holder_device_id)
                values (?, 'RCT', 'TILL_POSITION', ?, ?, ?, 'M001-K08S1-T1-RCT', 42, ?),
                       (?, 'GRN', 'LOCATION', ?, ?, null, 'M001-K08S1-GRN', 7, ?)
                """,
                POSITION_SERIES,
                ENTITY,
                SHOP,
                POSITION,
                DEVICE,
                LOCATION_SERIES,
                ENTITY,
                SHOP,
                DEVICE);
    }

    @Test
    void anAdministratorIssuesACodeAndTheDeviceSpendsItOnceForItsCredential() {
        ResponseEntity<JsonNode> issued = issueCode();

        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String code = issued.getBody().path("code").asText();
        assertThat(code).hasSize(16).matches("[A-HJ-NP-Z2-9]+");
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("DEVICE_ENROLMENT_CODE_ISSUED");
        assertThat(kernel.committedEvents()).hasAtLeastOneElementOfType(EnrolmentCodeIssued.class);
        // Stored as a hash only.
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select code_hash from kernel.device_enrolment_code where device_id = ?",
                                String.class,
                                DEVICE))
                .isNotEqualTo(code)
                .hasSize(64);
        kernel.reset();

        ResponseEntity<JsonNode> enrolled =
                enrol(code.substring(0, 4) + "-" + code.substring(4).toLowerCase(), SERIAL);

        assertThat(enrolled.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = enrolled.getBody();
        assertThat(body.path("device_id").asText()).isEqualTo(DEVICE.toString());
        assertThat(body.path("location_id").asText()).isEqualTo(SHOP.toString());
        assertThat(body.path("till_position_id").asText()).isEqualTo(POSITION.toString());
        assertThat(body.path("position_no").asInt()).isEqualTo(1);
        assertThat(body.path("primary_till").asBoolean()).isTrue();
        assertThat(body.path("credential").path("client_id").asText()).isEqualTo("device-" + DEVICE);
        assertThat(body.path("credential").path("client_secret").asText()).isEqualTo("the-secret");
        assertThat(body.path("next_device_seq").asLong()).isEqualTo(1);
        assertThat(body.path("series").findValuesAsText("doc_type_code")).containsExactlyInAnyOrder("RCT", "GRN");
        assertThat(body.path("series").findValues("next_number"))
                .extracting(JsonNode::asLong)
                .containsExactlyInAnyOrder(42L, 7L);
        assertThat(body.path("snapshot").path("full_snapshot_required").asBoolean())
                .isTrue();
        assertThat(body.path("signing_key").path("algorithm").asText()).isEqualTo("Ed25519");
        assertThat(body.path("signing_key").path("public_key").asText()).isNotBlank();
        assertThat(cursor()).isZero();
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("DEVICE_SYNC_ENROLLED");
        assertThat(kernel.committedEvents())
                .filteredOn(e -> e instanceof DeviceSyncEnrolled)
                .singleElement()
                .satisfies(e ->
                        assertThat(((DeviceSyncEnrolled) e).enrolledDeviceId()).isEqualTo(DEVICE));
        // The secret is in the answer to the device and nowhere else.
        assertThat(kernel.committedAudit().toString()).doesNotContain("the-secret");

        // Spent: the same code does not enrol twice.
        assertRefused(enrol(code, SERIAL), HttpStatus.FORBIDDEN, "sync.enrolment.code_invalid");

        // And the device, with the token its credential gives, uploads from sequence one.
        assertThat(upload(Ids.next(), 1, events(1, 1)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aWrongCodeOrAnotherSerialLearnsNothingAndSpendsNothing() {
        String code = issueCode().getBody().path("code").asText();

        assertRefused(enrol("AAAAAAAAAAAAAAAA", SERIAL), HttpStatus.FORBIDDEN, "sync.enrolment.code_invalid");
        assertRefused(enrol(code, "SN-SOMEONE-ELSE"), HttpStatus.FORBIDDEN, "sync.enrolment.code_invalid");
        assertRefused(enrolAs(Ids.next(), code, SERIAL), HttpStatus.FORBIDDEN, "sync.enrolment.code_invalid");
        verify(credentials, never()).issue(any(), any());

        assertThat(enrol(code, SERIAL).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anExpiredOrWithdrawnCodeIsRefused() {
        String first = issueCode().getBody().path("code").asText();
        String second = issueCode().getBody().path("code").asText();

        // Issuing the second withdrew the first.
        assertRefused(enrol(first, SERIAL), HttpStatus.FORBIDDEN, "sync.enrolment.code_invalid");

        superuserJdbc()
                .update(
                        "update kernel.device_enrolment_code set expires_at = now() - interval '1 minute' where device_id = ?",
                        DEVICE);
        assertRefused(enrol(second, SERIAL), HttpStatus.FORBIDDEN, "sync.enrolment.code_invalid");
    }

    @Test
    void aDeviceNotYetAtATillPositionIsToldSoAndKeepsItsCode() {
        superuserJdbc()
                .update(
                        "update party.device set status = 'ENROLLED', current_till_position_id = null where device_id = ?",
                        DEVICE);
        directory.invalidate(DEVICE);
        String code = issueCode().getBody().path("code").asText();

        assertRefused(enrol(code, SERIAL), HttpStatus.UNPROCESSABLE_ENTITY, "sync.enrolment.device_not_assigned");

        superuserJdbc()
                .update(
                        "update party.device set status = 'ACTIVE', current_till_position_id = ? where device_id = ?",
                        POSITION,
                        DEVICE);
        assertThat(enrol(code, SERIAL).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anAdministratorOfAnotherEntityOrADeviceOutOfServiceGetsNoCode() {
        ResponseEntity<JsonNode> foreign = post(
                "/v1/sync/devices/" + DEVICE + "/enrolment-codes",
                null,
                TestIdentityProvider.entityWideHeaders(ADMIN, OTHER_ENTITY));
        assertRefused(foreign, HttpStatus.UNPROCESSABLE_ENTITY, "sync.enrolment.device_not_enrollable");

        superuserJdbc()
                .update(
                        "update party.device set status = 'RETIRED', current_till_position_id = null where device_id = ?",
                        DEVICE);
        assertRefused(issueCode(), HttpStatus.UNPROCESSABLE_ENTITY, "sync.enrolment.device_not_enrollable");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.device_enrolment_code where device_id = ?",
                                Integer.class,
                                DEVICE))
                .isZero();
    }

    @Test
    void aRetryWithTheSameIdempotencyKeyIsAnsweredAgainAndAnotherKeyLearnsNothing() {
        String code = issueCode().getBody().path("code").asText();
        String key = UUID.randomUUID().toString();

        // The network dropped after central committed: the till holds nothing and retries.
        ResponseEntity<JsonNode> first = enrolWithKey(code, SERIAL, key);
        ResponseEntity<JsonNode> retry = enrolWithKey(code, SERIAL, key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().path("next_device_seq").asLong()).isEqualTo(1);
        assertThat(retry.getBody().path("credential").path("client_id").asText())
                .isEqualTo("device-" + DEVICE);
        // The first credential was never delivered: the retry is issued one of its own.
        verify(credentials, times(2)).issue(eq(DEVICE), eq(ENTITY));
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .filteredOn("DEVICE_SYNC_ENROLLED"::equals)
                .hasSize(2);

        // Another key with the spent code is a guess, and the answer is the one for every guess.
        assertRefused(
                enrolWithKey(code, SERIAL, UUID.randomUUID().toString()),
                HttpStatus.FORBIDDEN,
                "sync.enrolment.code_invalid");
        // A retry with the right key but another serial, too.
        assertRefused(enrolWithKey(code, "SN-SOMEONE-ELSE", key), HttpStatus.FORBIDDEN, "sync.enrolment.code_invalid");

        // A new code ends the retry window of the old one.
        issueCode();
        assertRefused(enrolWithKey(code, SERIAL, key), HttpStatus.FORBIDDEN, "sync.enrolment.code_invalid");
    }

    @Test
    void enrollingAgainRotatesTheCredentialAndKeepsTheSequence() {
        enrol(issueCode().getBody().path("code").asText(), SERIAL);
        upload(Ids.next(), 1, events(1, 7));

        ResponseEntity<JsonNode> again =
                enrol(issueCode().getBody().path("code").asText(), SERIAL);

        assertThat(again.getBody().path("next_device_seq").asLong()).isEqualTo(8);
        assertThat(cursor()).isEqualTo(7);
    }

    private ResponseEntity<JsonNode> issueCode() {
        return post(
                "/v1/sync/devices/" + DEVICE + "/enrolment-codes",
                null,
                TestIdentityProvider.entityWideHeaders(ADMIN, ENTITY));
    }

    private ResponseEntity<JsonNode> enrol(String code, String serial) {
        return enrolAs(DEVICE, code, serial);
    }

    /** Without a token: the enrolment code is the credential of this one call. */
    private ResponseEntity<JsonNode> enrolAs(UUID device, String code, String serial) {
        return post("/v1/sync/devices/" + device + "/enrol", enrolment(code, serial), new HttpHeaders());
    }

    /** The same, with the Idempotency-Key the till chose, for a retry. */
    private ResponseEntity<JsonNode> enrolWithKey(String code, String serial, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return http.exchange(
                "/v1/sync/devices/" + DEVICE + "/enrol",
                HttpMethod.POST,
                new HttpEntity<>(enrolment(code, serial), headers),
                JsonNode.class);
    }

    private ObjectNode enrolment(String code, String serial) {
        ObjectNode request = json.createObjectNode();
        request.put("enrolment_code", code);
        request.put("hardware_serial", serial);
        request.put("app_version", "1.0.0");
        return request;
    }

    private static void assertRefused(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().path("code").asText()).isEqualTo(code);
    }
}
