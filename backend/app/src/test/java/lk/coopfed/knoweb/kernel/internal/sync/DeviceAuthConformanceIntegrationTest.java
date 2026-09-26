package lk.coopfed.knoweb.kernel.internal.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Device authentication (19A section 2; doc 32 sections 3.3 step 1 and 9): the till's operations
 * take a device token and nothing else; a device token opens nothing else; the status is M1's,
 * through a cache M1's device events empty; a suspended device is refused with a revoke
 * instruction it can verify with the key of its enrolment.
 */
class DeviceAuthConformanceIntegrationTest extends SyncIntegrationTest {

    private static final UUID ADMIN = UUID.fromString("0190a800-0000-7000-8000-000000000501");

    @Autowired
    TillSigner signer;

    @Autowired
    DeviceCacheInvalidator invalidator;

    @Test
    void aUserTokenIsRefusedOnEveryDeviceOperation() {
        HttpHeaders user = TestIdentityProvider.entityWideHeaders(ADMIN, ENTITY);

        assertRefused(
                post("/v1/sync/devices/" + DEVICE + "/batches", batch(Ids.next(), 1, events(1, 1)), user),
                HttpStatus.FORBIDDEN,
                "sync.device_token_required");
        assertRefused(
                post(
                        "/v1/sync/devices/" + DEVICE + "/heartbeat",
                        json.createObjectNode().put("app_version", "1"),
                        TestIdentityProvider.entityWideHeaders(ADMIN, ENTITY)),
                HttpStatus.FORBIDDEN,
                "sync.device_token_required");
        assertRefused(
                get(
                        "/v1/sync/locations/" + SHOP + "/changes?since=0",
                        TestIdentityProvider.entityWideHeaders(ADMIN, ENTITY)),
                HttpStatus.FORBIDDEN,
                "sync.device_token_required");
        assertThat(cursor()).isZero();
    }

    @Test
    void aDeviceTokenOpensNothingButTheSyncOperations() {
        assertRefused(
                get("/v1/hello/greetings", TestIdentityProvider.deviceHeaders(DEVICE)),
                HttpStatus.FORBIDDEN,
                "sync.device_token_not_allowed");
        assertRefused(
                post(
                        "/v1/sync/devices/" + DEVICE + "/enrolment-codes",
                        null,
                        TestIdentityProvider.deviceHeaders(DEVICE)),
                HttpStatus.FORBIDDEN,
                "sync.device_token_not_allowed");
    }

    @Test
    void noTokenIsRefused() {
        ResponseEntity<JsonNode> response =
                post("/v1/sync/devices/" + DEVICE + "/batches", batch(Ids.next(), 1, events(1, 1)), new HttpHeaders());
        assertRefused(response, HttpStatus.UNAUTHORIZED, "auth.required");
    }

    @Test
    void aDeviceActsForItselfAndReadsItsOwnShopOnly() {
        UUID otherDevice = Ids.next();
        assertRefused(
                post(
                        "/v1/sync/devices/" + otherDevice + "/batches",
                        batch(Ids.next(), 1, events(1, 1)),
                        TestIdentityProvider.deviceHeaders(DEVICE)),
                HttpStatus.FORBIDDEN,
                "sync.device_mismatch");
        assertRefused(
                get(
                        "/v1/sync/locations/" + OTHER_SHOP + "/changes?since=0",
                        TestIdentityProvider.deviceHeaders(DEVICE)),
                HttpStatus.FORBIDDEN,
                "sync.location_mismatch");
    }

    @Test
    void aDeviceM1DoesNotKnowOrThatIsNotActiveAtAPositionIsRefused() {
        assertRefused(heartbeatOf(Ids.next()), HttpStatus.FORBIDDEN, "sync.device_unknown");

        superuserJdbc()
                .update(
                        "update party.device set status = 'ENROLLED', current_till_position_id = null where device_id = ?",
                        DEVICE);
        directory.invalidate(DEVICE);
        assertRefused(heartbeatOf(DEVICE), HttpStatus.FORBIDDEN, "sync.device_not_active");
    }

    @Test
    void aSuspendedDeviceIsRefusedWithASignedRevokeItCanVerify() throws Exception {
        superuserJdbc().update("update party.device set status = 'SUSPENDED' where device_id = ?", DEVICE);
        directory.invalidate(DEVICE);

        ResponseEntity<JsonNode> refused = upload(Ids.next(), 1, events(1, 1));

        assertRefused(refused, HttpStatus.FORBIDDEN, "sync.device_suspended");
        JsonNode revoke = refused.getBody().path("params").path("revoke");
        assertThat(revoke.path("type").asText()).isEqualTo("REVOKE");
        assertThat(revoke.path("device_id").asText()).isEqualTo(DEVICE.toString());
        assertThat(revoke.path("status").asText()).isEqualTo("SUSPENDED");
        assertThat(revoke.path("key_id").asText()).isEqualTo(signer.keyId());
        String signed = DeviceAuth.canonical(
                "REVOKE",
                DEVICE,
                "SUSPENDED",
                Instant.parse(revoke.path("issued_at").asText()));
        assertThat(verifies(signed, revoke.path("signature").asText(), signer.publicKeyBase64()))
                .isTrue();
        assertThat(verifies(
                        signed.replace("SUSPENDED", "ACTIVE"),
                        revoke.path("signature").asText(),
                        signer.publicKeyBase64()))
                .isFalse();
        // Refused before anything was touched: the unacknowledged rows stay with the till.
        assertThat(cursor()).isZero();
    }

    @Test
    void aRetiredDeviceIsRevokedToo() {
        superuserJdbc()
                .update(
                        "update party.device set status = 'RETIRED', current_till_position_id = null where device_id = ?",
                        DEVICE);
        directory.invalidate(DEVICE);

        ResponseEntity<JsonNode> refused = heartbeatOf(DEVICE);

        assertRefused(refused, HttpStatus.FORBIDDEN, "sync.device_retired");
        assertThat(refused.getBody()
                        .path("params")
                        .path("revoke")
                        .path("signature")
                        .asText())
                .isNotBlank();
    }

    @Test
    void theStatusIsCachedUntilM1sDeviceEventEmptiesTheCache() {
        assertThat(heartbeatOf(DEVICE).getStatusCode()).isEqualTo(HttpStatus.OK);

        // M1 suspends the device; until its event arrives this instance still has it ACTIVE.
        superuserJdbc().update("update party.device set status = 'SUSPENDED' where device_id = ?", DEVICE);
        assertThat(heartbeatOf(DEVICE).getStatusCode()).isEqualTo(HttpStatus.OK);

        invalidator.onDeviceChanged(
                json.createObjectNode().put("deviceId", DEVICE.toString()).put("reason", "lost"), null);

        assertRefused(heartbeatOf(DEVICE), HttpStatus.FORBIDDEN, "sync.device_suspended");
    }

    @Test
    void aDeviceAsksForAnUploadOnlyForADocumentCentralHolds() {
        var request = json.createObjectNode();
        request.put("document_id", Ids.next().toString());
        request.put("attachment_id", Ids.next().toString());
        request.put("content_type", "image/jpeg");
        request.put("sha256", "a".repeat(64));

        assertRefused(
                post("/v1/sync/attachments/presign", request, TestIdentityProvider.deviceHeaders(DEVICE)),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "attachment.document_not_found");
        assertRefused(
                post("/v1/sync/attachments/presign", request, TestIdentityProvider.entityWideHeaders(ADMIN, ENTITY)),
                HttpStatus.FORBIDDEN,
                "sync.device_token_required");
    }

    private ResponseEntity<JsonNode> heartbeatOf(UUID device) {
        return post(
                "/v1/sync/devices/" + device + "/heartbeat",
                json.createObjectNode().put("app_version", "1.0.0"),
                TestIdentityProvider.deviceHeaders(device));
    }

    private static void assertRefused(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().path("code").asText()).isEqualTo(code);
    }

    private static boolean verifies(String text, String signature, String publicKey) throws Exception {
        PublicKey key = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(text.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(signature));
    }
}
