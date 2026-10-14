package lk.coopfed.knoweb.m1party;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PinHasher;
import lk.coopfed.knoweb.m1party.api.BulkNetworkRegistered;
import lk.coopfed.knoweb.m1party.internal.bulk.BulkRegisterNetworkHandler;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test for bulk network registration (CAT-08): {@code POST /v1/party/locations/bulk}.
 *
 * <p>Proves:
 * <ul>
 *   <li>Performance: registering at least 40 shops, 120 users, and 60 devices completes within 60 seconds.</li>
 *   <li>Batch inserts: shops, till positions, users, and devices are created in one transaction.</li>
 *   <li>Security: PINs are hashed with Argon2id and matchable via {@link PinHasher}.</li>
 *   <li>Audit & events: commits {@code NETWORK_BULK_REGISTERED} audit and publishes {@code m1.network.bulk_registered.v1}.</li>
 *   <li>All-or-none rejection: any validation error rejects the entire payload without persisting any changes.</li>
 * </ul>
 */
class BulkRegisterNetworkIntegrationTest extends PostgresIntegrationTest {

    private static final String URL = "/v1/party/locations/bulk";
    private static final UUID ENTITY_ID = UUID.fromString("0190e000-0000-7000-8000-0000000000a1");
    private static final UUID ACTOR_ID = UUID.fromString("0190e000-0000-7000-8000-000000000501");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private PinHasher pinHasher;

    @BeforeEach
    void cleanAndSeedEntity() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table party.device, security.app_user, party.till_position, party.location cascade");
        admin.update(
                "insert into party.entity (entity_id, entity_code, entity_type, legal_name_en) values (?, ?, ?, ?) "
                        + "on conflict (entity_id) do update set entity_code = excluded.entity_code",
                ENTITY_ID,
                "MPCS01",
                "MPCS",
                "Colombo MPCS Society");
    }

    @Test
    void fortyShopsOneHundredTwentyUsersSixtyDevicesRegisterUnderSixtySeconds() {
        // Prepare 40 shops, 120 users (3 per shop for first 40 shops), and 60 devices
        List<Map<String, Object>> shops = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            String shopCode = String.format("SH%03d", i);
            shops.add(Map.of(
                    "locationCode",
                    shopCode,
                    "nameEn",
                    "Shop " + i,
                    "district",
                    "Colombo",
                    "language",
                    "en",
                    "sizeBand",
                    "M",
                    "tillPositions",
                    List.of(1)));
        }

        List<Map<String, Object>> users = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            int shopIndex = ((i - 1) % 40) + 1;
            String shopCode = String.format("SH%03d", shopIndex);
            users.add(Map.of(
                    "username",
                    String.format("user_%03d", i),
                    "displayName",
                    "Operator " + i,
                    "language",
                    "en",
                    "userKind",
                    "TILL",
                    "pin",
                    "1234",
                    "locationCode",
                    shopCode));
        }

        List<Map<String, Object>> devices = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            int shopIndex = ((i - 1) % 40) + 1;
            String shopCode = String.format("SH%03d", shopIndex);
            devices.add(Map.of(
                    "hardwareSerial",
                    String.format("POS-SER-%04d", i),
                    "deviceKind",
                    "POS_TERMINAL",
                    "locationCode",
                    shopCode,
                    "positionNo",
                    1,
                    "appVersion",
                    "1.0.0"));
        }

        Map<String, Object> request = Map.of(
                "shops", shops,
                "users", users,
                "devices", devices);

        Instant before = Instant.now();
        ResponseEntity<JsonNode> response = post(request);
        Duration elapsed = Duration.between(before, Instant.now());

        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);

        JsonNode report = response.getBody();
        assertThat(report).isNotNull();
        assertThat(report.get("status").asText()).isEqualTo("REGISTERED");
        assertThat(report.get("shopsRegistered").asInt()).isEqualTo(40);
        assertThat(report.get("positionsRegistered").asInt()).isEqualTo(40);
        assertThat(report.get("usersRegistered").asInt()).isEqualTo(120);
        assertThat(report.get("devicesRegistered").asInt()).isEqualTo(60);
        assertThat(report.get("problems")).isEmpty();

        // CAT-08 Performance Requirement: Must process within 60 seconds
        assertThat(elapsed)
                .as("Bulk network registration must finish well within 60 seconds")
                .isLessThan(Duration.ofSeconds(60));

        // Verify Database Records
        JdbcTemplate admin = superuserJdbc();
        int locCount = admin.queryForObject(
                "select count(*) from party.location where owner_entity_id = ?", Integer.class, ENTITY_ID);
        assertThat(locCount).isEqualTo(40);

        int posCount = admin.queryForObject(
                "select count(*) from party.till_position where owner_entity_id = ?", Integer.class, ENTITY_ID);
        assertThat(posCount).isEqualTo(40);

        int userCount = admin.queryForObject(
                "select count(*) from security.app_user where owner_entity_id = ?", Integer.class, ENTITY_ID);
        assertThat(userCount).isEqualTo(120);

        int devCount = admin.queryForObject(
                "select count(*) from party.device where owner_entity_id = ?", Integer.class, ENTITY_ID);
        assertThat(devCount).isEqualTo(60);

        // Verify Argon2id Password Hashing
        String samplePinHash = admin.queryForObject(
                "select pin_hash from security.app_user where username = 'user_001'", String.class);
        assertThat(samplePinHash).isNotBlank();
        assertThat(pinHasher.matches("1234", samplePinHash)).isTrue();

        // Verify Audit & Domain Events
        assertThat(kernel.committedAudit())
                .filteredOn(a -> BulkRegisterNetworkHandler.AUDIT_NETWORK_REGISTERED.equals(a.eventType()))
                .hasSize(1);

        assertThat(kernel.committedEvents()).hasSize(1).first().isInstanceOf(BulkNetworkRegistered.class);

        BulkNetworkRegistered event =
                (BulkNetworkRegistered) kernel.committedEvents().get(0);
        assertThat(event.entityId()).isEqualTo(ENTITY_ID);
        assertThat(event.shopsCount()).isEqualTo(40);
        assertThat(event.positionsCount()).isEqualTo(40);
        assertThat(event.usersCount()).isEqualTo(120);
        assertThat(event.devicesCount()).isEqualTo(60);
    }

    @Test
    void badRowRejectsEntirePayloadAllOrNoneWithProblemsReported() {
        // Shop with duplicate code, user with invalid PIN format
        Map<String, Object> request = Map.of(
                "shops",
                        List.of(
                                Map.of("locationCode", "SH_GOOD", "nameEn", "Good Shop"),
                                Map.of("locationCode", "SH_GOOD", "nameEn", "Duplicate Shop")),
                "users",
                        List.of(Map.of("username", "validuser", "displayName", "Valid", "pin", "abcde")), // invalid PIN
                "devices", List.of());

        ResponseEntity<JsonNode> response = post(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode report = response.getBody();
        assertThat(report).isNotNull();
        assertThat(report.get("status").asText()).isEqualTo("REJECTED");
        assertThat(report.get("shopsRegistered").asInt()).isZero();
        assertThat(report.get("usersRegistered").asInt()).isZero();
        assertThat(report.get("problems").size()).isGreaterThanOrEqualTo(2);

        // Database must remain untouched (all-or-none)
        JdbcTemplate admin = superuserJdbc();
        int locCount = admin.queryForObject(
                "select count(*) from party.location where owner_entity_id = ?", Integer.class, ENTITY_ID);
        assertThat(locCount).isZero();

        int userCount = admin.queryForObject(
                "select count(*) from security.app_user where owner_entity_id = ?", Integer.class, ENTITY_ID);
        assertThat(userCount).isZero();

        // No audit or events committed
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    private ResponseEntity<JsonNode> post(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityProvider.entityWideToken(ACTOR_ID, ENTITY_ID));
        headers.set("X-Scope-Entity", ENTITY_ID.toString());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        return http.exchange(URL, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
