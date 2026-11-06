package lk.coopfed.knoweb.m3pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.testsupport.PinnedClock;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CustomerPriceLookupPostgresIntegrationTest (M3-08):
 * Explicitly proves that:
 * 1. A customer can look up an item and see the current public retail price resolved
 *    via the authoritative core engine.
 * 2. Gazetted maximum retail control prices are respected and exposed safely.
 * 3. Internal cost, margin, batch reference, price list ID, policy algorithms, and restricted
 *    owner data are NOT returned in the API response.
 * 4. Customer price search across shop catalog works accurately without data leakage.
 */
@Import(PinnedClock.class)
class CustomerPriceLookupPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID SOCIETY = UUID.fromString("0190e760-0000-7000-8000-000000000005");
    private static final UUID SHOP = UUID.fromString("0190e760-0000-7000-8000-000000000105");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e760-0000-7000-8000-000000000100");

    @Autowired
    TestRestTemplate http;

    private final UUID rice = Ids.next();
    private final UUID sugar = Ids.next();
    private final LocalDate today = PinnedClock.TODAY;
    private final LocalDate yesterday = today.minusDays(1);
    private final LocalDate tomorrow = today.plusDays(1);

    @BeforeEach
    void arrange() {
        clean();
        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'Each', false), ('KG', 'Kilogram', true) on conflict do nothing");
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)"
                        + " values (?, 'M3RCUST', 'M3 retail tax', ?) on conflict do nothing",
                TAX_CATEGORY,
                FEDERATION);
        admin.update(
                """
                insert into party.entity (entity_id, entity_code, entity_type, legal_name_en, district,
                    financial_year_start_month, default_language, status)
                values (?, 'M3CSOC', 'MPCS', 'M3 Customer Society', 'Kurunegala', 1, 'si', 'ACTIVE')
                on conflict (entity_id) do nothing
                """,
                SOCIETY);
        admin.update(
                "insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en)"
                        + " values (?, ?, 'M3CSHOP', 'SHOP', 'M3 Customer Shop') on conflict (location_id) do nothing",
                SHOP,
                SOCIETY);
        admin.update(
                "insert into catalogue.sku (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si,"
                        + " short_name_ta, base_uom_code, sold_by_weight, tax_category_id)"
                        + " values (?, 'M3C-RICE', ?, 'SHARED', 'Samba Rice 1kg', 'සම්බා සහල්', 'சம்பா அரிசி', 'EA', false, ?)",
                rice,
                FEDERATION,
                TAX_CATEGORY);
        admin.update(
                "insert into catalogue.sku (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si,"
                        + " short_name_ta, base_uom_code, sold_by_weight, tax_category_id)"
                        + " values (?, 'M3C-SUGAR', ?, 'SHARED', 'White Sugar 1kg', 'සුදු සීනි', 'வெள்ளை சீனி', 'KG', true, ?)",
                sugar,
                FEDERATION,
                TAX_CATEGORY);

        // Add a barcode row for rice
        admin.update(
                "insert into catalogue.sku_barcode (barcode, symbology, sku_id, uom_code, owner_entity_id, status)"
                        + " values ('479000100010', 'EAN13', ?, 'EA', ?, 'ACTIVE')",
                rice,
                FEDERATION);

        kernel.reset();

        // 1. Enter gazetted control price for rice (Rs. 220.00)
        send(
                HttpMethod.POST,
                "/v1/pricing/control-prices",
                FEDERATION,
                Map.of(
                        "skuId",
                        rice,
                        "ceilingPrice",
                        220.0,
                        "ceilingUomCode",
                        "EA",
                        "effectiveFrom",
                        yesterday.toString(),
                        "gazetteReference",
                        "2492/29"));

        // 2. Publish society shelf price list (Rice at Rs. 215.00, Sugar at Rs. 240.00)
        ResponseEntity<JsonNode> created = send(
                HttpMethod.POST,
                "/v1/pricing/lists",
                SOCIETY,
                Map.of("kind", "RETAIL", "name", "Customer Shelf Prices"));
        String listId = created.getBody().get("priceListId").asText();

        send(
                HttpMethod.PUT,
                "/v1/pricing/lists/" + listId + "/lines",
                SOCIETY,
                Map.of(
                        "lines",
                        List.of(
                                Map.of("skuId", rice, "uomCode", "EA", "tierFromQty", 0, "price", 215.0),
                                Map.of("skuId", sugar, "uomCode", "KG", "tierFromQty", 0, "price", 240.0))));

        send(
                HttpMethod.POST,
                "/v1/pricing/lists/" + listId + "/publish",
                SOCIETY,
                Map.of("applyFrom", tomorrow.toString()));
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table pricing.label_job, pricing.price_list_line, pricing.price_list cascade");
        admin.execute("truncate table pricing.control_price, pricing.mrp_policy");
        admin.execute("truncate table catalogue.sku_barcode, catalogue.sku cascade");
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
        admin.execute("truncate table kernel.audit_event");
    }

    @Test
    void customerCanLookUpItemAndSeeCurrentPublicPrice() {
        // Look up rice at the shop
        ResponseEntity<JsonNode> response = send(
                HttpMethod.GET,
                "/v1/pricing/customer/lookup?locationId=" + SHOP + "&skuId=" + rice + "&date=" + tomorrow,
                SOCIETY,
                null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();

        // 1. Proves current public price is returned
        assertThat(body.get("skuId").asText()).isEqualTo(rice.toString());
        assertThat(body.get("skuCode").asText()).isEqualTo("M3C-RICE");
        assertThat(body.get("productName").asText()).isEqualTo("Samba Rice 1kg");
        assertThat(body.get("uom").asText()).isEqualTo("EA");
        assertThat(body.get("publicPrice").decimalValue()).isEqualByComparingTo("215.00");
        assertThat(body.get("currency").asText()).isEqualTo("LKR");
        assertThat(body.get("isAvailable").asBoolean()).isTrue();

        // 2. Proves control price ceiling is reflected
        assertThat(body.get("controlPrice").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(body.get("isPriceControlled").asBoolean()).isTrue();
        assertThat(body.get("locationId").asText()).isEqualTo(SHOP.toString());
        assertThat(body.get("locationName").asText()).isEqualTo("M3 Customer Shop");
    }

    @Test
    void internalCostMarginAndRestrictedOwnerDataAreNotReturnedInApiResponse() {
        // Look up product via public customer lookup endpoint
        ResponseEntity<JsonNode> response = send(
                HttpMethod.GET,
                "/v1/pricing/customer/lookup?locationId=" + SHOP + "&skuId=" + rice + "&date=" + tomorrow,
                SOCIETY,
                null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();

        // Strictly verify that internal-only cost, margin, and restricted owner data are NOT in response
        assertThat(body.has("cost")).as("internal cost must not be returned").isFalse();
        assertThat(body.has("unitCost"))
                .as("internal unit cost must not be returned")
                .isFalse();
        assertThat(body.has("purchaseCost"))
                .as("internal purchase cost must not be returned")
                .isFalse();
        assertThat(body.has("margin"))
                .as("internal margin must not be returned")
                .isFalse();
        assertThat(body.has("marginPercent"))
                .as("internal margin percent must not be returned")
                .isFalse();
        assertThat(body.has("priceListId"))
                .as("internal priceListId must not be returned")
                .isFalse();
        assertThat(body.has("batchId"))
                .as("internal batchId must not be returned")
                .isFalse();
        assertThat(body.has("ownerEntityId"))
                .as("restricted ownerEntityId must not be returned")
                .isFalse();
        assertThat(body.has("policy"))
                .as("internal store policy algorithm must not be returned")
                .isFalse();
        assertThat(body.has("engineVersion"))
                .as("internal engine version must not be returned")
                .isFalse();
        assertThat(body.has("supplierId"))
                .as("supplier identity must not be returned")
                .isFalse();
    }

    @Test
    void customerLookupByBarcodeResolvesCorrectPublicPrice() {
        // Look up product via barcode "479000100010"
        ResponseEntity<JsonNode> response = send(
                HttpMethod.GET,
                "/v1/pricing/customer/lookup?locationId=" + SHOP + "&barcode=479000100010&date=" + tomorrow,
                SOCIETY,
                null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("skuCode").asText()).isEqualTo("M3C-RICE");
        assertThat(body.get("publicPrice").decimalValue()).isEqualByComparingTo("215.00");
        assertThat(body.get("isAvailable").asBoolean()).isTrue();
    }

    @Test
    void customerPriceSearchReturnsPublicPricesAcrossStoreWithoutDataLeakage() {
        // Search products matching "Sugar"
        ResponseEntity<JsonNode> response = send(
                HttpMethod.GET,
                "/v1/pricing/customer/search?locationId=" + SHOP + "&query=Sugar&date=" + tomorrow,
                SOCIETY,
                null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode list = response.getBody();
        assertThat(list).isNotNull();
        assertThat(list.isArray()).isTrue();
        assertThat(list).hasSize(1);

        JsonNode sugarNode = list.get(0);
        assertThat(sugarNode.get("skuCode").asText()).isEqualTo("M3C-SUGAR");
        assertThat(sugarNode.get("publicPrice").decimalValue()).isEqualByComparingTo("240.00");
        assertThat(sugarNode.get("uom").asText()).isEqualTo("KG");

        // Verify zero leakage in search results
        assertThat(sugarNode.has("cost")).isFalse();
        assertThat(sugarNode.has("margin")).isFalse();
        assertThat(sugarNode.has("priceListId")).isFalse();
        assertThat(sugarNode.has("batchId")).isFalse();
        assertThat(sugarNode.has("ownerEntityId")).isFalse();
    }

    @Test
    void unpricedOrNonexistentLocationReturnsNotFound() {
        // Random location not visible / not found
        ResponseEntity<JsonNode> response = send(
                HttpMethod.GET,
                "/v1/pricing/customer/lookup?locationId=" + Ids.next() + "&skuId=" + rice + "&date=" + tomorrow,
                SOCIETY,
                null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<JsonNode> send(HttpMethod method, String url, UUID entity, Object body) {
        HttpHeaders headers = TestIdentityProvider.entityWideHeaders(UUID.randomUUID(), entity);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (method != HttpMethod.GET) {
            headers.set("Idempotency-Key", UUID.randomUUID().toString());
        }
        return http.exchange(url, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
