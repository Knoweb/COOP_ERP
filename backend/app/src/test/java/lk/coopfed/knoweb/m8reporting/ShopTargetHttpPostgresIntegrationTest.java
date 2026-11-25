package lk.coopfed.knoweb.m8reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
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

class ShopTargetHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY_A = UUID.fromString("0190e888-0000-7000-8000-000000000001");
    private static final UUID ENTITY_B = UUID.fromString("0190e888-0000-7000-8000-000000000002");
    private static final UUID USER_A = UUID.fromString("0190e888-0000-7000-8000-000000000010");
    private static final UUID USER_B = UUID.fromString("0190e888-0000-7000-8000-000000000020");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper mapper;

    private UUID shopA1;
    private UUID shopA2;
    private UUID shopB1;

    @BeforeEach
    void setUp() {
        superuserJdbc().execute("TRUNCATE reporting.shop_target CASCADE");
        superuserJdbc().execute("TRUNCATE reporting.shop_sale_fact CASCADE");
        superuserJdbc()
                .execute(
                        "DELETE FROM party.location WHERE owner_entity_id IN ('" + ENTITY_A + "', '" + ENTITY_B + "')");

        shopA1 = Ids.next();
        shopA2 = Ids.next();
        shopB1 = Ids.next();

        // Seed locations in party.location
        superuserJdbc()
                .update(
                        """
                INSERT INTO party.location (location_id, owner_entity_id, location_code, location_type, name_en)
                VALUES (?, ?, ?, 'SHOP', ?)
                """,
                        shopA1,
                        ENTITY_A,
                        "SH-A1",
                        "Shop A1 Colombo");

        superuserJdbc()
                .update(
                        """
                INSERT INTO party.location (location_id, owner_entity_id, location_code, location_type, name_en)
                VALUES (?, ?, ?, 'SHOP', ?)
                """,
                        shopA2,
                        ENTITY_A,
                        "SH-A2",
                        "Shop A2 Kandy");

        superuserJdbc()
                .update(
                        """
                INSERT INTO party.location (location_id, owner_entity_id, location_code, location_type, name_en)
                VALUES (?, ?, ?, 'SHOP', ?)
                """,
                        shopB1,
                        ENTITY_B,
                        "SH-B1",
                        "Shop B1 Galle");
    }

    @Test
    void createAndGetTargetAsEntityManager() {
        HttpHeaders headers = asEntity(USER_A, ENTITY_A);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "locationId", shopA1.toString(),
                "periodStart", "2026-10-01",
                "periodEnd", "2026-10-31",
                "targetAmount", "100000.00");

        ResponseEntity<JsonNode> created = http.exchange(
                "/v1/reporting/shop-targets", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode responseBody = created.getBody();
        assertThat(responseBody).isNotNull();
        String targetIdStr = responseBody.get("targetId").asText();
        assertThat(targetIdStr).isNotBlank();
        assertThat(responseBody.get("locationId").asText()).isEqualTo(shopA1.toString());
        assertThat(responseBody.get("periodStart").asText()).isEqualTo("2026-10-01");
        assertThat(responseBody.get("periodEnd").asText()).isEqualTo("2026-10-31");
        assertThat(responseBody.get("targetAmount").asText()).isEqualTo("100000.00");
        assertThat(responseBody.get("currency").asText()).isEqualTo("LKR");

        // Verify audit and event
        assertThat(kernel.committedAudit()).extracting("eventType").contains("SHOP_TARGET_CREATED");
        assertThat(kernel.committedEvents())
                .singleElement()
                .isInstanceOf(lk.coopfed.knoweb.m8reporting.api.ShopTargetCreated.class);

        // Get target by ID
        ResponseEntity<JsonNode> fetched = http.exchange(
                "/v1/reporting/shop-targets/" + targetIdStr,
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("targetId").asText()).isEqualTo(targetIdStr);
        assertThat(fetched.getBody().get("targetAmount").asText()).isEqualTo("100000.00");
    }

    @Test
    void updateTargetSuccessfully() {
        HttpHeaders headers = asEntity(USER_A, ENTITY_A);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> createBody = Map.of(
                "locationId", shopA1.toString(),
                "periodStart", "2026-10-01",
                "periodEnd", "2026-10-31",
                "targetAmount", "100000.00");

        ResponseEntity<JsonNode> created = http.exchange(
                "/v1/reporting/shop-targets", HttpMethod.POST, new HttpEntity<>(createBody, headers), JsonNode.class);

        String targetId = created.getBody().get("targetId").asText();

        // Update target
        HttpHeaders updateHeaders = asEntity(USER_A, ENTITY_A);
        updateHeaders.set("Idempotency-Key", UUID.randomUUID().toString());
        updateHeaders.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> updateBody = Map.of(
                "periodStart", "2026-10-01",
                "periodEnd", "2026-11-15",
                "targetAmount", "150000.00");

        ResponseEntity<JsonNode> updated = http.exchange(
                "/v1/reporting/shop-targets/" + targetId,
                HttpMethod.PUT,
                new HttpEntity<>(updateBody, updateHeaders),
                JsonNode.class);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().get("periodEnd").asText()).isEqualTo("2026-11-15");
        assertThat(updated.getBody().get("targetAmount").asText()).isEqualTo("150000.00");

        assertThat(kernel.committedAudit()).extracting("eventType").contains("SHOP_TARGET_UPDATED");
        assertThat(kernel.committedEvents())
                .last()
                .isInstanceOf(lk.coopfed.knoweb.m8reporting.api.ShopTargetUpdated.class);
    }

    @Test
    void tenantIsolationEnforcedByRowLevelSecurity() {
        // Create target for Entity A
        HttpHeaders headersA = asEntity(USER_A, ENTITY_A);
        headersA.set("Idempotency-Key", UUID.randomUUID().toString());
        headersA.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> bodyA = Map.of(
                "locationId", shopA1.toString(),
                "periodStart", "2026-10-01",
                "periodEnd", "2026-10-31",
                "targetAmount", "100000.00");

        ResponseEntity<JsonNode> created = http.exchange(
                "/v1/reporting/shop-targets", HttpMethod.POST, new HttpEntity<>(bodyA, headersA), JsonNode.class);

        String targetId = created.getBody().get("targetId").asText();

        // Entity B attempts to get Entity A's target -> 404
        ResponseEntity<JsonNode> fetchByB = http.exchange(
                "/v1/reporting/shop-targets/" + targetId,
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_B, ENTITY_B)),
                JsonNode.class);

        assertThat(fetchByB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Entity B queries performance report -> sees 0 items
        ResponseEntity<JsonNode> perfB = http.exchange(
                "/v1/reporting/shop-targets/performance",
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_B, ENTITY_B)),
                JsonNode.class);

        assertThat(perfB.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(perfB.getBody().get("items")).isEmpty();

        // User scoped to shop A2 cannot see target of shop A1
        HttpHeaders shopA2Headers = asLocation(USER_A, ENTITY_A, shopA2);
        ResponseEntity<JsonNode> perfShopA2 = http.exchange(
                "/v1/reporting/shop-targets/performance",
                HttpMethod.GET,
                new HttpEntity<>(shopA2Headers),
                JsonNode.class);

        assertThat(perfShopA2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(perfShopA2.getBody().get("items")).isEmpty();
    }

    @Test
    void refusedRulesFailWithExpectedErrorCodesAndCommitNothing() {
        HttpHeaders headers = asEntity(USER_A, ENTITY_A);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Negative target amount violates OpenAPI regex pattern -> 400
        ResponseEntity<JsonNode> negative = http.exchange(
                "/v1/reporting/shop-targets",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "locationId", shopA1.toString(),
                                "periodStart", "2026-10-01",
                                "periodEnd", "2026-10-31",
                                "targetAmount", "-100.00"),
                        headers),
                JsonNode.class);
        assertThat(negative.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Period start > period end -> 422 m8.target.period_invalid
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> invertedDates = http.exchange(
                "/v1/reporting/shop-targets",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "locationId", shopA1.toString(),
                                "periodStart", "2026-10-31",
                                "periodEnd", "2026-10-01",
                                "targetAmount", "100000.00"),
                        headers),
                JsonNode.class);
        assertThat(invertedDates.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(invertedDates.getBody().get("code").asText()).isEqualTo("m8.target.period_invalid");

        // Location of other entity is hidden by RLS -> 422 m8.target.location_unknown
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> wrongLocation = http.exchange(
                "/v1/reporting/shop-targets",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "locationId", shopB1.toString(),
                                "periodStart", "2026-10-01",
                                "periodEnd", "2026-10-31",
                                "targetAmount", "100000.00"),
                        headers),
                JsonNode.class);
        assertThat(wrongLocation.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(wrongLocation.getBody().get("code").asText()).isEqualTo("m8.target.location_unknown");

        // Location mismatch for shop-scoped manager -> 422 m8.target.location_scope_mismatch
        HttpHeaders shopScoped = asLocation(USER_A, ENTITY_A, shopA1);
        shopScoped.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> mismatch = http.exchange(
                "/v1/reporting/shop-targets",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "locationId", shopA2.toString(),
                                "periodStart", "2026-10-01",
                                "periodEnd", "2026-10-31",
                                "targetAmount", "100000.00"),
                        shopScoped),
                JsonNode.class);
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mismatch.getBody().get("code").asText()).isEqualTo("m8.target.location_scope_mismatch");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void performanceComparisonComputesActualVarianceAndAchievementPercent() {
        // 1. Create target of 1000.00 for Shop A1 from 2026-10-01 to 2026-10-15
        HttpHeaders headers = asEntity(USER_A, ENTITY_A);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        http.exchange(
                "/v1/reporting/shop-targets",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "locationId", shopA1.toString(),
                                "periodStart", "2026-10-01",
                                "periodEnd", "2026-10-15",
                                "targetAmount", "1000.00"),
                        headers),
                JsonNode.class);

        // 2. Insert sales in shop_sale_fact
        // Sale 1: in period, 600.00
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_sale_fact
                       (receipt_id, owner_entity_id, location_id, business_date, doc_number, net, tax, gross, lines, occurred_at, event_id)
                VALUES (?, ?, ?, date '2026-10-05', 'RCP-01', 550.00, 50.00, 600.00, 2, now(), ?)
                """,
                        Ids.next(),
                        ENTITY_A,
                        shopA1,
                        Ids.next());

        // Sale 2: in period, 650.00
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_sale_fact
                       (receipt_id, owner_entity_id, location_id, business_date, doc_number, net, tax, gross, lines, occurred_at, event_id)
                VALUES (?, ?, ?, date '2026-10-10', 'RCP-02', 600.00, 50.00, 650.00, 3, now(), ?)
                """,
                        Ids.next(),
                        ENTITY_A,
                        shopA1,
                        Ids.next());

        // Sale 3: OUTSIDE period (2026-10-20), 500.00
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_sale_fact
                       (receipt_id, owner_entity_id, location_id, business_date, doc_number, net, tax, gross, lines, occurred_at, event_id)
                VALUES (?, ?, ?, date '2026-10-20', 'RCP-03', 450.00, 50.00, 500.00, 1, now(), ?)
                """,
                        Ids.next(),
                        ENTITY_A,
                        shopA1,
                        Ids.next());

        // 3. Query performance
        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/shop-targets/performance?locationId=" + shopA1,
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = response.getBody().get("items");
        assertThat(items).hasSize(1);
        JsonNode item = items.get(0);

        assertThat(item.get("targetAmount").asText()).isEqualTo("1000.00");
        assertThat(item.get("actualAmount").asText()).isEqualTo("1250.00");
        assertThat(item.get("varianceAmount").asText()).isEqualTo("250.00");
        assertThat(item.get("achievementPercent").asText()).isEqualTo("125.0");
        assertThat(item.get("locationCode").asText()).isEqualTo("SH-A1");
        assertThat(item.get("locationName").asText()).isEqualTo("SH-A1 Shop A1 Colombo");
    }

    @Test
    void zeroTargetHandlesAchievementPercentAsNull() {
        HttpHeaders headers = asEntity(USER_A, ENTITY_A);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        http.exchange(
                "/v1/reporting/shop-targets",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "locationId", shopA2.toString(),
                                "periodStart", "2026-10-01",
                                "periodEnd", "2026-10-15",
                                "targetAmount", "0.00"),
                        headers),
                JsonNode.class);

        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_sale_fact
                       (receipt_id, owner_entity_id, location_id, business_date, doc_number, net, tax, gross, lines, occurred_at, event_id)
                VALUES (?, ?, ?, date '2026-10-05', 'RCP-10', 300.00, 0.00, 300.00, 1, now(), ?)
                """,
                        Ids.next(),
                        ENTITY_A,
                        shopA2,
                        Ids.next());

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/shop-targets/performance?locationId=" + shopA2,
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode item = response.getBody().get("items").get(0);
        assertThat(item.get("targetAmount").asText()).isEqualTo("0.00");
        assertThat(item.get("actualAmount").asText()).isEqualTo("300.00");
        assertThat(item.get("varianceAmount").asText()).isEqualTo("300.00");
        assertThat(item.get("achievementPercent").isNull()).isTrue();
    }

    private static HttpHeaders asEntity(UUID user, UUID entity) {
        return TestIdentityProvider.entityWideHeaders(user, entity);
    }

    private static HttpHeaders asLocation(UUID user, UUID entity, UUID location) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityProvider.token(
                user, entity, "OWN", claims -> claims.claim("scopes", List.of(entity + "/" + location))));
        headers.set("X-Scope-Entity", entity.toString());
        headers.set("X-Scope-Location", location.toString());
        return headers;
    }
}
