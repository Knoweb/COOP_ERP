package lk.coopfed.knoweb.m8reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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

class RevenueEstimationHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY_A = UUID.fromString("0190e888-0000-7000-8000-000000000001");
    private static final UUID ENTITY_B = UUID.fromString("0190e888-0000-7000-8000-000000000002");
    private static final UUID USER_A = UUID.fromString("0190e888-0000-7000-8000-000000000010");
    private static final UUID USER_B = UUID.fromString("0190e888-0000-7000-8000-000000000020");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e888-0000-7000-8000-000000000099");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper mapper;

    private UUID locA1;
    private UUID locA2;
    private UUID locB1;

    private UUID skuRice;
    private UUID skuSugar;
    private UUID skuDhal;
    private UUID skuNoPrice;

    private UUID retailListA;
    private UUID commercialListA;

    @BeforeEach
    void setUp() {
        clean();

        locA1 = Ids.next();
        locA2 = Ids.next();
        locB1 = Ids.next();

        skuRice = Ids.next();
        skuSugar = Ids.next();
        skuDhal = Ids.next();
        skuNoPrice = Ids.next();

        // Seed entities
        superuserJdbc()
                .update(
                        """
                INSERT INTO party.entity (entity_id, entity_code, entity_type, legal_name_en, district,
                    financial_year_start_month, default_language, status)
                VALUES (?, 'ENT-A', 'MPCS', 'Polgolla Society A', 'Kandy', 1, 'en', 'ACTIVE'),
                       (?, 'ENT-B', 'MPCS', 'Galle Society B', 'Galle', 1, 'en', 'ACTIVE')
                ON CONFLICT (entity_id) DO NOTHING
                """,
                        ENTITY_A,
                        ENTITY_B);

        // Seed locations
        insertLocation(locA1, ENTITY_A, "LOC-A1", "Polgolla Main Branch");
        insertLocation(locA2, ENTITY_A, "LOC-A2", "Coop City Kandy");
        insertLocation(locB1, ENTITY_B, "LOC-B1", "Galle Branch");

        // Seed UOM and Tax category
        superuserJdbc()
                .update(
                        "INSERT INTO catalogue.uom (uom_code, name_en, is_weight) VALUES ('EA', 'Each', false), ('KG', 'Kilogram', true) ON CONFLICT DO NOTHING");
        superuserJdbc()
                .update(
                        "INSERT INTO catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id) VALUES (?, 'TAX-EST', 'Standard Tax', ?) ON CONFLICT DO NOTHING",
                        TAX_CATEGORY,
                        ENTITY_A);

        // Seed SKUs (sold_by_weight: false for Rice & Sugar, true for Dhal)
        insertSku(skuRice, "SKU-RICE-1", "Samba Rice 1kg", "EA", false);
        insertSku(skuSugar, "SKU-SUGR-1", "White Sugar 1kg", "EA", false);
        insertSku(skuDhal, "SKU-DHAL-1", "Mysore Dhal", "KG", true);
        insertSku(skuNoPrice, "SKU-NOPR-1", "Unpriced Item", "EA", false);

        // Seed Retail Price List for Entity A (create as DRAFT, insert lines, then publish)
        retailListA = Ids.next();
        superuserJdbc()
                .update(
                        """
                INSERT INTO pricing.price_list (price_list_id, owner_entity_id, kind, name, version, root_price_list_id, status, apply_from)
                VALUES (?, ?, 'RETAIL', 'Entity A Retail List', 1, ?, 'DRAFT', '2024-01-01')
                """,
                        retailListA,
                        ENTITY_A,
                        retailListA);

        insertPriceLine(retailListA, skuRice, "EA", new BigDecimal("300.00"), ENTITY_A);
        insertPriceLine(retailListA, skuSugar, "EA", new BigDecimal("250.00"), ENTITY_A);
        insertPriceLine(retailListA, skuDhal, "KG", new BigDecimal("400.00"), ENTITY_A);

        superuserJdbc()
                .update(
                        "UPDATE pricing.price_list SET status = 'PUBLISHED', published_at = now() WHERE price_list_id = ?",
                        retailListA);

        // Seed Commercial Price List for Entity A: Rice @ 280.00 (create as DRAFT, insert lines, then publish)
        commercialListA = Ids.next();
        superuserJdbc()
                .update(
                        """
                INSERT INTO pricing.price_list (price_list_id, owner_entity_id, kind, name, version, root_price_list_id, status, apply_from)
                VALUES (?, ?, 'COMMERCIAL', 'Entity A Commercial List', 1, ?, 'DRAFT', '2024-01-01')
                """,
                        commercialListA,
                        ENTITY_A,
                        commercialListA);

        insertPriceLine(commercialListA, skuRice, "EA", new BigDecimal("280.00"), ENTITY_A);

        superuserJdbc()
                .update(
                        "UPDATE pricing.price_list SET status = 'PUBLISHED', published_at = now() WHERE price_list_id = ?",
                        commercialListA);
    }

    private void clean() {
        superuserJdbc().execute("TRUNCATE pricing.price_list_line, pricing.price_list CASCADE");
        superuserJdbc().execute("TRUNCATE catalogue.sku CASCADE");
        superuserJdbc()
                .execute(
                        "DELETE FROM party.location WHERE owner_entity_id IN ('" + ENTITY_A + "', '" + ENTITY_B + "')");
    }

    private void insertLocation(UUID locId, UUID entityId, String code, String name) {
        superuserJdbc()
                .update(
                        "INSERT INTO party.location (location_id, owner_entity_id, location_code, location_type, name_en) VALUES (?, ?, ?, 'SHOP', ?)",
                        locId,
                        entityId,
                        code,
                        name);
    }

    private void insertSku(UUID skuId, String code, String name, String uom, boolean isWeight) {
        superuserJdbc()
                .update(
                        """
                INSERT INTO catalogue.sku (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si, short_name_ta,
                                           base_uom_code, sold_by_weight, has_printed_mrp, tax_category_id)
                VALUES (?, ?, ?, 'SHARED', ?, ?, ?, ?, ?, false, ?)
                """,
                        skuId,
                        code,
                        ENTITY_A,
                        name,
                        name,
                        name,
                        uom,
                        isWeight,
                        TAX_CATEGORY);
    }

    private void insertPriceLine(UUID priceListId, UUID skuId, String uom, BigDecimal price, UUID entityId) {
        superuserJdbc()
                .update(
                        """
                INSERT INTO pricing.price_list_line (line_id, price_list_id, sku_id, uom_code, tier_from_qty, price, effective_from, owner_entity_id)
                VALUES (?, ?, ?, ?, 0, ?, '2024-01-01', ?)
                """,
                        Ids.next(),
                        priceListId,
                        skuId,
                        uom,
                        price,
                        entityId);
    }

    private HttpHeaders requestHeaders(UUID user, UUID entity) {
        HttpHeaders headers = TestIdentityProvider.entityWideHeaders(user, entity);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void validOneSkuEstimate() {
        Map<String, Object> request = Map.of(
                "targetRevenue",
                "10000.00",
                "locationId",
                locA1.toString(),
                "priceType",
                "RETAIL",
                "items",
                List.of(Map.of("skuId", skuSugar.toString(), "mixPercent", "100.00")));

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/revenue-estimates",
                HttpMethod.POST,
                new HttpEntity<>(request, requestHeaders(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("targetRevenue").asText()).isEqualTo("10000.00");
        assertThat(body.get("totalEstimatedRevenue").asText()).isEqualTo("10000.00");
        assertThat(body.get("targetVariance").asText()).isEqualTo("0.00");
        assertThat(body.get("currency").asText()).isEqualTo("LKR");

        JsonNode items = body.get("items");
        assertThat(items.size()).isEqualTo(1);
        JsonNode item = items.get(0);
        assertThat(item.get("skuId").asText()).isEqualTo(skuSugar.toString());
        assertThat(item.get("unitPrice").asText()).isEqualTo("250.0000");
        assertThat(item.get("mixPercent").asText()).isEqualTo("100.00");
        assertThat(item.get("targetShare").asText()).isEqualTo("10000.00");
        // 10000 / 250 = 40 units
        assertThat(item.get("rawQuantity").asText()).isEqualTo("40.0000");
        assertThat(item.get("roundedQuantity").asText()).isEqualTo("40");
        assertThat(item.get("estimatedRevenue").asText()).isEqualTo("10000.00");
        assertThat(item.get("roundingVariance").asText()).isEqualTo("0.00");
    }

    @Test
    void validMultiSkuMix() {
        Map<String, Object> request = Map.of(
                "targetRevenue",
                "100000.00",
                "locationId",
                locA1.toString(),
                "priceType",
                "RETAIL",
                "items",
                List.of(
                        Map.of("skuId", skuRice.toString(), "mixPercent", "60.00"),
                        Map.of("skuId", skuSugar.toString(), "mixPercent", "40.00")));

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/revenue-estimates",
                HttpMethod.POST,
                new HttpEntity<>(request, requestHeaders(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("targetRevenue").asText()).isEqualTo("100000.00");
        assertThat(body.get("totalEstimatedRevenue").asText()).isEqualTo("100000.00");
        assertThat(body.get("targetVariance").asText()).isEqualTo("0.00");

        JsonNode items = body.get("items");
        assertThat(items.size()).isEqualTo(2);

        // Rice: 60% of 100,000 = 60,000 / 300 = 200 units
        JsonNode riceItem = items.get(0);
        assertThat(riceItem.get("targetShare").asText()).isEqualTo("60000.00");
        assertThat(riceItem.get("roundedQuantity").asText()).isEqualTo("200");
        assertThat(riceItem.get("estimatedRevenue").asText()).isEqualTo("60000.00");

        // Sugar: 40% of 100,000 = 40,000 / 250 = 160 units
        JsonNode sugarItem = items.get(1);
        assertThat(sugarItem.get("targetShare").asText()).isEqualTo("40000.00");
        assertThat(sugarItem.get("roundedQuantity").asText()).isEqualTo("160");
        assertThat(sugarItem.get("estimatedRevenue").asText()).isEqualTo("40000.00");
    }

    @Test
    void mixTotalInvalidRefused() {
        // Mix sums to 80% instead of 100%
        Map<String, Object> request = Map.of(
                "targetRevenue", "50000.00",
                "locationId", locA1.toString(),
                "items",
                        List.of(
                                Map.of("skuId", skuRice.toString(), "mixPercent", "50.00"),
                                Map.of("skuId", skuSugar.toString(), "mixPercent", "30.00")));

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/revenue-estimates",
                HttpMethod.POST,
                new HttpEntity<>(request, requestHeaders(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("m8.estimator.mix_must_equal_100");
    }

    @Test
    void zeroOrNegativeTargetRefused() {
        Map<String, Object> request = Map.of(
                "targetRevenue", "0.00",
                "locationId", locA1.toString(),
                "items", List.of(Map.of("skuId", skuRice.toString(), "mixPercent", "100.00")));

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/revenue-estimates",
                HttpMethod.POST,
                new HttpEntity<>(request, requestHeaders(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("m8.estimator.target_revenue_invalid");
    }

    @Test
    void duplicateSkuRefused() {
        Map<String, Object> request = Map.of(
                "targetRevenue", "50000.00",
                "locationId", locA1.toString(),
                "items",
                        List.of(
                                Map.of("skuId", skuRice.toString(), "mixPercent", "50.00"),
                                Map.of("skuId", skuRice.toString(), "mixPercent", "50.00")));

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/revenue-estimates",
                HttpMethod.POST,
                new HttpEntity<>(request, requestHeaders(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("m8.estimator.duplicate_sku");
    }

    @Test
    void unresolvedPriceHandledExplicitly() {
        // skuNoPrice has no price list line in M3
        Map<String, Object> request = Map.of(
                "targetRevenue", "50000.00",
                "locationId", locA1.toString(),
                "items", List.of(Map.of("skuId", skuNoPrice.toString(), "mixPercent", "100.00")));

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/revenue-estimates",
                HttpMethod.POST,
                new HttpEntity<>(request, requestHeaders(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("m8.estimator.unresolved_price");
    }

    @Test
    void priceChangeChangesEstimatePredictably() {
        // Commercial price for Rice is 280.00; Retail is 300.00
        Map<String, Object> retailRequest = Map.of(
                "targetRevenue",
                "84000.00",
                "locationId",
                locA1.toString(),
                "priceType",
                "RETAIL",
                "items",
                List.of(Map.of("skuId", skuRice.toString(), "mixPercent", "100.00")));

        ResponseEntity<JsonNode> retailResponse = http.exchange(
                "/v1/reporting/revenue-estimates",
                HttpMethod.POST,
                new HttpEntity<>(retailRequest, requestHeaders(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(retailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        // At retail price 300: 84000 / 300 = 280 units
        assertThat(retailResponse
                        .getBody()
                        .get("items")
                        .get(0)
                        .get("roundedQuantity")
                        .asText())
                .isEqualTo("280");

        Map<String, Object> commercialRequest = Map.of(
                "targetRevenue",
                "84000.00",
                "locationId",
                locA1.toString(),
                "priceType",
                "COMMERCIAL",
                "items",
                List.of(Map.of("skuId", skuRice.toString(), "mixPercent", "100.00")));

        ResponseEntity<JsonNode> commercialResponse = http.exchange(
                "/v1/reporting/revenue-estimates",
                HttpMethod.POST,
                new HttpEntity<>(commercialRequest, requestHeaders(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(commercialResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        // At commercial price 280: 84000 / 280 = 300 units
        assertThat(commercialResponse
                        .getBody()
                        .get("items")
                        .get(0)
                        .get("roundedQuantity")
                        .asText())
                .isEqualTo("300");
    }

    @Test
    void mixChangeChangesEstimatePredictably() {
        // Test 50/50 mix
        Map<String, Object> req5050 = Map.of(
                "targetRevenue", "100000.00",
                "locationId", locA1.toString(),
                "items",
                        List.of(
                                Map.of("skuId", skuRice.toString(), "mixPercent", "50.00"),
                                Map.of("skuId", skuSugar.toString(), "mixPercent", "50.00")));

        ResponseEntity<JsonNode> resp5050 = http.exchange(
                "/v1/reporting/revenue-estimates",
                HttpMethod.POST,
                new HttpEntity<>(req5050, requestHeaders(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(resp5050.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp5050.getBody().get("items").get(0).get("targetShare").asText())
                .isEqualTo("50000.00");
        assertThat(resp5050.getBody().get("items").get(1).get("targetShare").asText())
                .isEqualTo("50000.00");

        // Shift to 80/20 mix
        Map<String, Object> req8020 = Map.of(
                "targetRevenue", "100000.00",
                "locationId", locA1.toString(),
                "items",
                        List.of(
                                Map.of("skuId", skuRice.toString(), "mixPercent", "80.00"),
                                Map.of("skuId", skuSugar.toString(), "mixPercent", "20.00")));

        ResponseEntity<JsonNode> resp8020 = http.exchange(
                "/v1/reporting/revenue-estimates",
                HttpMethod.POST,
                new HttpEntity<>(req8020, requestHeaders(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(resp8020.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp8020.getBody().get("items").get(0).get("targetShare").asText())
                .isEqualTo("80000.00");
        assertThat(resp8020.getBody().get("items").get(1).get("targetShare").asText())
                .isEqualTo("20000.00");
    }

    @Test
    void crossTenantAndLocationIsolation() {
        // User A attempts to plan for Entity B's location
        Map<String, Object> crossEntityRequest = Map.of(
                "targetRevenue", "50000.00",
                "locationId", locB1.toString(),
                "items", List.of(Map.of("skuId", skuRice.toString(), "mixPercent", "100.00")));

        ResponseEntity<JsonNode> crossEntityResp = http.exchange(
                "/v1/reporting/revenue-estimates",
                HttpMethod.POST,
                new HttpEntity<>(crossEntityRequest, requestHeaders(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(crossEntityResp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(crossEntityResp.getBody().get("code").asText()).isEqualTo("m8.estimator.location_unknown");

        // User scoped to locA1 attempts to plan with locA2 -> 403 Forbidden
        HttpHeaders scopedHeaders = new HttpHeaders();
        scopedHeaders.setBearerAuth(TestIdentityProvider.token(
                USER_A, ENTITY_A, "OWN", claims -> claims.claim("scopes", List.of(ENTITY_A + "/" + locA1))));
        scopedHeaders.set("X-Scope-Entity", ENTITY_A.toString());
        scopedHeaders.set("X-Scope-Location", locA1.toString());
        scopedHeaders.set("Idempotency-Key", UUID.randomUUID().toString());
        scopedHeaders.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> forbiddenRequest = Map.of(
                "targetRevenue", "50000.00",
                "locationId", locA2.toString(),
                "items", List.of(Map.of("skuId", skuRice.toString(), "mixPercent", "100.00")));

        ResponseEntity<JsonNode> forbiddenResp = http.exchange(
                "/v1/reporting/revenue-estimates",
                HttpMethod.POST,
                new HttpEntity<>(forbiddenRequest, scopedHeaders),
                JsonNode.class);

        assertThat(forbiddenResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unauthorizedCallerRefused() {
        Map<String, Object> request = Map.of(
                "targetRevenue", "10000.00",
                "locationId", locA1.toString(),
                "items", List.of(Map.of("skuId", skuRice.toString(), "mixPercent", "100.00")));

        HttpHeaders deviceHeaders = TestIdentityProvider.deviceHeaders(UUID.randomUUID());
        deviceHeaders.set("Idempotency-Key", UUID.randomUUID().toString());
        deviceHeaders.setContentType(MediaType.APPLICATION_JSON);

        // Device token (cashier/till) does not have rpt.target.view
        ResponseEntity<JsonNode> forbidden = http.exchange(
                "/v1/reporting/revenue-estimates",
                HttpMethod.POST,
                new HttpEntity<>(request, deviceHeaders),
                JsonNode.class);

        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void deterministicRoundingAndVariance() {
        // Discrete item (EA): Rice @ 300.00 with target revenue 1000.00
        // Raw qty = 1000 / 300 = 3.3333
        // Rounded integer = 3 units -> estimated revenue = 3 * 300 = 900.00
        // Variance = 900 - 1000 = -100.00
        Map<String, Object> discreteRequest = Map.of(
                "targetRevenue", "1000.00",
                "locationId", locA1.toString(),
                "items", List.of(Map.of("skuId", skuRice.toString(), "mixPercent", "100.00")));

        ResponseEntity<JsonNode> discreteResp = http.exchange(
                "/v1/reporting/revenue-estimates",
                HttpMethod.POST,
                new HttpEntity<>(discreteRequest, requestHeaders(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(discreteResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode discreteItem = discreteResp.getBody().get("items").get(0);
        assertThat(discreteItem.get("rawQuantity").asText()).isEqualTo("3.3333");
        assertThat(discreteItem.get("roundedQuantity").asText()).isEqualTo("3");
        assertThat(discreteItem.get("estimatedRevenue").asText()).isEqualTo("900.00");
        assertThat(discreteItem.get("roundingVariance").asText()).isEqualTo("-100.00");
        assertThat(discreteResp.getBody().get("totalEstimatedRevenue").asText()).isEqualTo("900.00");
        assertThat(discreteResp.getBody().get("targetVariance").asText()).isEqualTo("-100.00");

        // Weighted item (KG): Dhal @ 400.00 with target revenue 1000.00
        // Raw qty = 1000 / 400 = 2.5000 -> Rounded qty = 2.500 KG -> Revenue = 1000.00
        Map<String, Object> weightRequest = Map.of(
                "targetRevenue", "1000.00",
                "locationId", locA1.toString(),
                "items", List.of(Map.of("skuId", skuDhal.toString(), "mixPercent", "100.00")));

        ResponseEntity<JsonNode> weightResp = http.exchange(
                "/v1/reporting/revenue-estimates",
                HttpMethod.POST,
                new HttpEntity<>(weightRequest, requestHeaders(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(weightResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode weightItem = weightResp.getBody().get("items").get(0);
        assertThat(weightItem.get("rawQuantity").asText()).isEqualTo("2.5000");
        assertThat(weightItem.get("roundedQuantity").asText()).isEqualTo("2.500");
        assertThat(weightItem.get("estimatedRevenue").asText()).isEqualTo("1000.00");
        assertThat(weightItem.get("roundingVariance").asText()).isEqualTo("0.00");
    }
}
