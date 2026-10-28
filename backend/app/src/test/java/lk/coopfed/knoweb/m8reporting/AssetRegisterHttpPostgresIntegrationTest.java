package lk.coopfed.knoweb.m8reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.m8reporting.api.AssetRegistered;
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

class AssetRegisterHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY_A = UUID.fromString("0190e888-0000-7000-8000-000000000001");
    private static final UUID ENTITY_B = UUID.fromString("0190e888-0000-7000-8000-000000000002");
    private static final UUID USER_A = UUID.fromString("0190e888-0000-7000-8000-000000000010");
    private static final UUID USER_B = UUID.fromString("0190e888-0000-7000-8000-000000000020");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper mapper;

    private UUID locA1;
    private UUID locA2;
    private UUID locB1;

    @BeforeEach
    void setUp() {
        superuserJdbc().execute("TRUNCATE reporting.asset CASCADE");
        superuserJdbc()
                .execute(
                        "DELETE FROM party.location WHERE owner_entity_id IN ('" + ENTITY_A + "', '" + ENTITY_B + "')");

        locA1 = Ids.next();
        locA2 = Ids.next();
        locB1 = Ids.next();

        insertLocation(locA1, ENTITY_A, "LOC-A1", "Polgolla Branch 1");
        insertLocation(locA2, ENTITY_A, "LOC-A2", "Coop City Kandy");
        insertLocation(locB1, ENTITY_B, "LOC-B1", "Galle Branch");
    }

    private void insertLocation(UUID locId, UUID entityId, String code, String name) {
        superuserJdbc()
                .update(
                        """
                        INSERT INTO party.location (location_id, owner_entity_id, location_code, location_type, name_en)
                        VALUES (?, ?, ?, 'SHOP', ?)
                        """,
                        locId,
                        entityId,
                        code,
                        name);
    }

    @Test
    void createAndListAssetsSuccessfully() {
        HttpHeaders headers = asEntity(USER_A, ENTITY_A);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "assetCode", "AST-LAND-01",
                "name", "Polgolla Main Branch Land",
                "description", "Commercial property deed #1042",
                "assetType", "LAND",
                "locationId", locA1.toString(),
                "acquisitionDate", "2024-03-15",
                "acquisitionCost", "15000000.00",
                "status", "ACTIVE");

        ResponseEntity<JsonNode> created =
                http.exchange("/v1/reporting/assets", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode resp = created.getBody();
        assertThat(resp).isNotNull();
        String assetId = resp.get("assetId").asText();
        assertThat(assetId).isNotBlank();
        assertThat(resp.get("assetCode").asText()).isEqualTo("AST-LAND-01");
        assertThat(resp.get("name").asText()).isEqualTo("Polgolla Main Branch Land");
        assertThat(resp.get("description").asText()).isEqualTo("Commercial property deed #1042");
        assertThat(resp.get("assetType").asText()).isEqualTo("LAND");
        assertThat(resp.get("locationId").asText()).isEqualTo(locA1.toString());
        assertThat(resp.get("locationName").asText()).isEqualTo("LOC-A1 Polgolla Branch 1");
        assertThat(resp.get("acquisitionDate").asText()).isEqualTo("2024-03-15");
        assertThat(new BigDecimal(resp.get("acquisitionCost").asText()))
                .isEqualByComparingTo(new BigDecimal("15000000.00"));
        assertThat(resp.get("status").asText()).isEqualTo("ACTIVE");

        // Verify audit record committed
        assertThat(kernel.committedAudit()).extracting("eventType").contains("ASSET_REGISTERED");

        // Verify domain event committed and does NOT contain sensitive "name"
        assertThat(kernel.committedEvents()).singleElement().isInstanceOf(AssetRegistered.class);
        AssetRegistered event = (AssetRegistered) kernel.committedEvents().get(0);
        assertThat(event.assetId().toString()).isEqualTo(assetId);
        assertThat(event.ownerEntityId()).isEqualTo(ENTITY_A);
        assertThat(event.assetCode()).isEqualTo("AST-LAND-01");
        assertThat(event.assetType()).isEqualTo("LAND");
        assertThat(event.locationId()).isEqualTo(locA1);

        // List assets
        ResponseEntity<JsonNode> list = http.exchange(
                "/v1/reporting/assets", HttpMethod.GET, new HttpEntity<>(asEntity(USER_A, ENTITY_A)), JsonNode.class);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = list.getBody().get("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("assetCode").asText()).isEqualTo("AST-LAND-01");

        // Retrieve single asset
        ResponseEntity<JsonNode> single = http.exchange(
                "/v1/reporting/assets/" + assetId,
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_A, ENTITY_A)),
                JsonNode.class);
        assertThat(single.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(single.getBody().get("assetCode").asText()).isEqualTo("AST-LAND-01");
    }

    @Test
    void filterByLocationReturnsAssignedAssets() {
        createAsset("AST-01", "Branch 1 Asset", "EQUIPMENT", locA1, "ACTIVE");
        createAsset("AST-02", "Branch 2 Asset", "FURNITURE", locA2, "ACTIVE");
        createAsset("AST-03", "HQ Vehicle", "VEHICLE", null, "ACTIVE");

        // Filter by location 1
        ResponseEntity<JsonNode> loc1Response = http.exchange(
                "/v1/reporting/assets?locationId=" + locA1,
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_A, ENTITY_A)),
                JsonNode.class);
        assertThat(loc1Response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode loc1Items = loc1Response.getBody().get("items");
        assertThat(loc1Items).hasSize(1);
        assertThat(loc1Items.get(0).get("assetCode").asText()).isEqualTo("AST-01");

        // Filter by location 2
        ResponseEntity<JsonNode> loc2Response = http.exchange(
                "/v1/reporting/assets?locationId=" + locA2,
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_A, ENTITY_A)),
                JsonNode.class);
        assertThat(loc2Response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode loc2Items = loc2Response.getBody().get("items");
        assertThat(loc2Items).hasSize(1);
        assertThat(loc2Items.get(0).get("assetCode").asText()).isEqualTo("AST-02");

        // Filter by type VEHICLE
        ResponseEntity<JsonNode> vehicleResponse = http.exchange(
                "/v1/reporting/assets?type=VEHICLE",
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_A, ENTITY_A)),
                JsonNode.class);
        assertThat(vehicleResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode vehicleItems = vehicleResponse.getBody().get("items");
        assertThat(vehicleItems).hasSize(1);
        assertThat(vehicleItems.get(0).get("assetCode").asText()).isEqualTo("AST-03");

        // Filter by status ACTIVE
        ResponseEntity<JsonNode> allActive = http.exchange(
                "/v1/reporting/assets?status=ACTIVE",
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_A, ENTITY_A)),
                JsonNode.class);
        assertThat(allActive.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allActive.getBody().get("items")).hasSize(3);
    }

    @Test
    void crossEntityTenantIsolation() {
        UUID assetIdA = createAsset("AST-ENT-A", "Entity A Asset", "BUILDING", locA1, "ACTIVE");

        // Entity B caller lists assets - cannot see Entity A's assets
        ResponseEntity<JsonNode> listB = http.exchange(
                "/v1/reporting/assets", HttpMethod.GET, new HttpEntity<>(asEntity(USER_B, ENTITY_B)), JsonNode.class);
        assertThat(listB.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listB.getBody().get("items")).isEmpty();

        // Entity B caller attempts to get Entity A's asset directly - 404 NOT_FOUND
        ResponseEntity<JsonNode> getB = http.exchange(
                "/v1/reporting/assets/" + assetIdA,
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_B, ENTITY_B)),
                JsonNode.class);
        assertThat(getB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void siblingLocationIsolation() {
        createAsset("AST-LOC1", "Branch 1 Machine", "EQUIPMENT", locA1, "ACTIVE");
        UUID assetIdLoc2 = createAsset("AST-LOC2", "Branch 2 Machine", "EQUIPMENT", locA2, "ACTIVE");
        createAsset("AST-HQ", "Headquarters Van", "VEHICLE", null, "ACTIVE");

        // User scoped to locA1 (Shop 1)
        HttpHeaders shop1Headers = asLocation(USER_A, ENTITY_A, locA1);

        // List assets as Shop 1: can only see locA1 assets; cannot see locA2 or null-location (entity-wide) assets
        ResponseEntity<JsonNode> listShop1 =
                http.exchange("/v1/reporting/assets", HttpMethod.GET, new HttpEntity<>(shop1Headers), JsonNode.class);
        assertThat(listShop1.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = listShop1.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("assetCode").asText()).isEqualTo("AST-LOC1");

        // Shop 1 caller attempts to retrieve sibling shop's asset directly
        ResponseEntity<JsonNode> getLoc2 = http.exchange(
                "/v1/reporting/assets/" + assetIdLoc2, HttpMethod.GET, new HttpEntity<>(shop1Headers), JsonNode.class);
        assertThat(getLoc2.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void locationScopedInsertWithoutLocationOrMismatchRefused() {
        HttpHeaders shop1Headers = asLocation(USER_A, ENTITY_A, locA1);
        shop1Headers.set("Idempotency-Key", UUID.randomUUID().toString());
        shop1Headers.setContentType(MediaType.APPLICATION_JSON);

        // 1. Try to create entity-wide asset without location
        Map<String, Object> bodyNullLoc = Map.of(
                "assetCode", "AST-FAIL-1",
                "name", "Illegal Entity-Wide Asset",
                "assetType", "OTHER",
                "status", "ACTIVE");

        ResponseEntity<JsonNode> respNullLoc = http.exchange(
                "/v1/reporting/assets", HttpMethod.POST, new HttpEntity<>(bodyNullLoc, shop1Headers), JsonNode.class);
        assertThat(respNullLoc.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(respNullLoc.getBody().get("code").asText()).isEqualTo("m8.asset.location_scope_mismatch");

        // 2. Try to create asset for sibling location
        Map<String, Object> bodySiblingLoc = Map.of(
                "assetCode", "AST-FAIL-2",
                "name", "Illegal Sibling Asset",
                "assetType", "OTHER",
                "locationId", locA2.toString(),
                "status", "ACTIVE");

        ResponseEntity<JsonNode> respSiblingLoc = http.exchange(
                "/v1/reporting/assets",
                HttpMethod.POST,
                new HttpEntity<>(bodySiblingLoc, shop1Headers),
                JsonNode.class);
        assertThat(respSiblingLoc.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(respSiblingLoc.getBody().get("code").asText()).isEqualTo("m8.asset.location_scope_mismatch");
    }

    @Test
    void duplicateAssetCodeRefused() {
        createAsset("AST-DUP", "First Asset", "EQUIPMENT", locA1, "ACTIVE");

        HttpHeaders headers = asEntity(USER_A, ENTITY_A);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "assetCode", "AST-DUP",
                "name", "Second Asset Same Code",
                "assetType", "FURNITURE",
                "locationId", locA1.toString(),
                "status", "ACTIVE");

        kernel.reset();

        ResponseEntity<JsonNode> response =
                http.exchange("/v1/reporting/assets", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("m8.asset.duplicate_code");

        // Assert failed command produced no audit or event side-effects
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void invalidRequestBodyRefused() {
        HttpHeaders headers = asEntity(USER_A, ENTITY_A);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Missing required name
        Map<String, Object> bodyNoName = Map.of(
                "assetCode", "AST-INVALID",
                "assetType", "EQUIPMENT");

        ResponseEntity<JsonNode> resp = http.exchange(
                "/v1/reporting/assets", HttpMethod.POST, new HttpEntity<>(bodyNoName, headers), JsonNode.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unauthorizedDeviceRefused() {
        ResponseEntity<JsonNode> forbidden = http.exchange(
                "/v1/reporting/assets",
                HttpMethod.GET,
                new HttpEntity<>(TestIdentityProvider.deviceHeaders(UUID.randomUUID())),
                JsonNode.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private UUID createAsset(String code, String name, String type, UUID locationId, String status) {
        HttpHeaders headers = asEntity(USER_A, ENTITY_A);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        java.util.HashMap<String, Object> body = new java.util.HashMap<>();
        body.put("assetCode", code);
        body.put("name", name);
        body.put("assetType", type);
        if (locationId != null) {
            body.put("locationId", locationId.toString());
        }
        body.put("status", status);

        ResponseEntity<JsonNode> response =
                http.exchange("/v1/reporting/assets", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().get("assetId").asText());
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
