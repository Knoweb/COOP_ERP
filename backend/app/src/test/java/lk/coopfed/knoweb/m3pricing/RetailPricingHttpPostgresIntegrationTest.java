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
 * The M3-06 and M3-07 operations of openapi/m3pricing.yaml through HTTP: control prices (enter,
 * list with history, rescind), the MRP policy (set, list, effective), a RETAIL list refused above a
 * control price with the binding ceiling in the outcome, published under it, the shelf price at a
 * shop, and the Federation's advisory lines. Dates as in RetailPricingPostgresIntegrationTest:
 * ceilings from yesterday, lists and prices from tomorrow, so a run across midnight holds.
 */
@Import(PinnedClock.class)
class RetailPricingHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID SOCIETY = UUID.fromString("0190e760-0000-7000-8000-000000000005");
    private static final UUID SHOP = UUID.fromString("0190e760-0000-7000-8000-000000000105");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e760-0000-7000-8000-000000000100");

    @Autowired
    TestRestTemplate http;

    private final UUID rice = Ids.next();
    private final LocalDate today = PinnedClock.TODAY;
    private final LocalDate yesterday = today.minusDays(1);
    private final LocalDate tomorrow = today.plusDays(1);

    @BeforeEach
    void arrange() {
        clean();
        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'Each', false) on conflict do nothing");
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)"
                        + " values (?, 'M3RHTTP', 'M3 retail HTTP tax', ?) on conflict do nothing",
                TAX_CATEGORY,
                FEDERATION);
        admin.update(
                """
                insert into party.entity (entity_id, entity_code, entity_type, legal_name_en, district,
                    financial_year_start_month, default_language, status)
                values (?, 'M3HSOC', 'MPCS', 'M3 HTTP society', 'Kurunegala', 1, 'si', 'ACTIVE')
                on conflict (entity_id) do nothing
                """,
                SOCIETY);
        admin.update(
                "insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en)"
                        + " values (?, ?, 'M3HSHOP', 'SHOP', 'M3 HTTP shop') on conflict (location_id) do nothing",
                SHOP,
                SOCIETY);
        admin.update(
                "insert into catalogue.sku (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si,"
                        + " short_name_ta, base_uom_code, sold_by_weight, tax_category_id)"
                        + " values (?, 'M3H-RICE', ?, 'SHARED', 'Rice', 'Rice', 'Rice', 'EA', false, ?)",
                rice,
                FEDERATION,
                TAX_CATEGORY);
        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table pricing.label_job, pricing.price_list_line, pricing.price_list cascade");
        admin.execute("truncate table pricing.control_price, pricing.mrp_policy");
        admin.execute("truncate table catalogue.sku cascade");
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
        admin.execute("truncate table kernel.audit_event");
    }

    @Test
    void aShelfLabelJobIsQueuedAndReadThroughTheGeneratedHttpContract() {
        ResponseEntity<JsonNode> created =
                send(HttpMethod.POST, "/v1/pricing/lists", SOCIETY, Map.of("kind", "RETAIL", "name", "Labels"));
        String listId = created.getBody().get("priceListId").asText();
        send(
                HttpMethod.PUT,
                "/v1/pricing/lists/" + listId + "/lines",
                SOCIETY,
                Map.of("lines", List.of(Map.of("skuId", rice, "uomCode", "EA", "tierFromQty", 0, "price", 215))));
        send(
                HttpMethod.POST,
                "/v1/pricing/lists/" + listId + "/publish",
                SOCIETY,
                Map.of("applyFrom", today.toString()));

        ResponseEntity<JsonNode> queued = send(
                HttpMethod.POST, "/v1/pricing/labels", SOCIETY, Map.of("locationId", SHOP, "skuIds", List.of(rice)));

        assertThat(queued.getStatusCode()).as(String.valueOf(queued.getBody())).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(queued.getBody().get("status").asText()).isEqualTo("QUEUED");
        assertThat(queued.getBody().get("requestedCount").asInt()).isEqualTo(1);
        assertThat(queued.getBody().get("renderedCount").asInt()).isZero();
        assertThat(queued.getBody().get("refused")).isEmpty();
        String jobId = queued.getBody().get("jobId").asText();

        ResponseEntity<JsonNode> same = send(HttpMethod.GET, "/v1/pricing/labels/" + jobId, SOCIETY, null);
        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(same.getBody().get("jobId").asText()).isEqualTo(jobId);
        assertThat(send(HttpMethod.GET, "/v1/pricing/labels/" + jobId, FEDERATION, null)
                        .getStatusCode())
                .as("another entity cannot discover the society's job")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void controlPricesAndTheMrpPolicyFollowTheSlice() {
        ResponseEntity<JsonNode> refused = send(HttpMethod.POST, "/v1/pricing/control-prices", SOCIETY, gazette("220"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("m3.control_price.federation_only");

        ResponseEntity<JsonNode> entered =
                send(HttpMethod.POST, "/v1/pricing/control-prices", FEDERATION, gazette("220"));
        assertThat(entered.getStatusCode())
                .as(String.valueOf(entered.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        String id = entered.getBody().get("controlPriceId").asText();
        assertThat(entered.getHeaders().getLocation()).hasToString("/v1/pricing/control-prices/" + id);
        assertThat(entered.getBody().get("ceilingPrice").decimalValue()).isEqualByComparingTo("220");

        ResponseEntity<JsonNode> inForce =
                send(HttpMethod.GET, "/v1/pricing/control-prices?inForceOn=" + tomorrow, SOCIETY, null);
        assertThat(inForce.getBody()).hasSize(1);

        ResponseEntity<JsonNode> rescinded = send(
                HttpMethod.POST,
                "/v1/pricing/control-prices/" + id + "/rescind",
                FEDERATION,
                Map.of("lastDay", tomorrow.toString(), "reason", "Withdrawn", "gazetteReference", "2494/02"));
        assertThat(rescinded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rescinded.getBody().get("effectiveTo").asText()).isEqualTo(tomorrow.toString());
        assertThat(send(HttpMethod.GET, "/v1/pricing/control-prices?skuId=" + rice, SOCIETY, null)
                        .getBody())
                .as("the history keeps the rescinded row")
                .hasSize(1);

        ResponseEntity<JsonNode> policy = send(
                HttpMethod.PUT,
                "/v1/pricing/mrp-policies",
                SOCIETY,
                Map.of("skuId", rice, "policy", "PICKER", "gapAmount", 25));
        assertThat(policy.getStatusCode()).as(String.valueOf(policy.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(policy.getBody().get("source").asText()).isEqualTo("OWN");
        assertThat(policy.getBody().get("gapAmount").decimalValue()).isEqualByComparingTo("25");
        assertThat(send(HttpMethod.GET, "/v1/pricing/mrp-policies", SOCIETY, null)
                        .getBody())
                .hasSize(1);
        assertThat(send(HttpMethod.GET, "/v1/pricing/mrp-policies/effective?skuId=" + rice, FEDERATION, null)
                        .getBody()
                        .get("source")
                        .asText())
                .isEqualTo("DEFAULT");

        ResponseEntity<JsonNode> badGap = send(
                HttpMethod.PUT,
                "/v1/pricing/mrp-policies",
                SOCIETY,
                Map.of("skuId", rice, "policy", "AUTO_LOWEST", "gapAmount", 25));
        assertThat(badGap.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(badGap.getBody().get("code").asText()).isEqualTo("m3.mrp_policy.gap_not_allowed");
    }

    @Test
    void aShelfListIsPublishedUnderTheControlPriceAndTheShopPriceResolves() {
        send(HttpMethod.POST, "/v1/pricing/control-prices", FEDERATION, gazette("220"));

        ResponseEntity<JsonNode> created =
                send(HttpMethod.POST, "/v1/pricing/lists", SOCIETY, Map.of("kind", "RETAIL", "name", "Shelf prices"));
        assertThat(created.getStatusCode())
                .as(String.valueOf(created.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        String listId = created.getBody().get("priceListId").asText();

        ResponseEntity<JsonNode> above = send(
                HttpMethod.PUT,
                "/v1/pricing/lists/" + listId + "/lines",
                SOCIETY,
                Map.of("lines", List.of(Map.of("skuId", rice, "uomCode", "EA", "tierFromQty", 0, "price", 240))));
        assertThat(above.getBody().get("saved").asBoolean()).isFalse();
        JsonNode outcome = above.getBody().get("outcomes").get(0);
        assertThat(outcome.get("reason").asText()).isEqualTo("m3.price_list.line.above_control_price");
        assertThat(outcome.get("ceilingKind").asText()).isEqualTo("CONTROL_PRICE");
        assertThat(outcome.get("ceilingValue").decimalValue()).isEqualByComparingTo("220");
        assertThat(outcome.get("ceilingRef").asText()).isEqualTo("2492/29");

        ResponseEntity<JsonNode> under = send(
                HttpMethod.PUT,
                "/v1/pricing/lists/" + listId + "/lines",
                SOCIETY,
                Map.of("lines", List.of(Map.of("skuId", rice, "uomCode", "EA", "tierFromQty", 0, "price", 215))));
        assertThat(under.getBody().get("saved").asBoolean()).isTrue();
        ResponseEntity<JsonNode> published = send(
                HttpMethod.POST,
                "/v1/pricing/lists/" + listId + "/publish",
                SOCIETY,
                Map.of("applyFrom", tomorrow.toString()));
        assertThat(published.getStatusCode())
                .as(String.valueOf(published.getBody()))
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> price = send(
                HttpMethod.GET,
                "/v1/pricing/resolve/retail?locationId=" + SHOP + "&skuId=" + rice + "&uom=EA&qty=1&date=" + tomorrow,
                SOCIETY,
                null);
        assertThat(price.getStatusCode()).as(String.valueOf(price.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(price.getBody().get("sellable").asBoolean()).isTrue();
        assertThat(price.getBody().get("unitPrice").decimalValue()).isEqualByComparingTo("215");
        assertThat(price.getBody().get("controlPrice").decimalValue()).isEqualByComparingTo("220");
        assertThat(price.getBody().get("capReason").asText()).isEqualTo("NONE");

        assertThat(send(
                                HttpMethod.GET,
                                "/v1/pricing/resolve/retail?locationId=" + Ids.next() + "&skuId=" + rice
                                        + "&uom=EA&qty=1&date=" + tomorrow,
                                SOCIETY,
                                null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<JsonNode> advisory =
                send(HttpMethod.POST, "/v1/pricing/lists", FEDERATION, Map.of("kind", "ADVISORY", "name", "Advice"));
        String advisoryId = advisory.getBody().get("priceListId").asText();
        send(
                HttpMethod.PUT,
                "/v1/pricing/lists/" + advisoryId + "/lines",
                FEDERATION,
                Map.of("lines", List.of(Map.of("skuId", rice, "uomCode", "EA", "tierFromQty", 0, "price", 210))));
        send(
                HttpMethod.POST,
                "/v1/pricing/lists/" + advisoryId + "/publish",
                FEDERATION,
                Map.of("applyFrom", tomorrow.toString()));
        ResponseEntity<JsonNode> advice =
                send(HttpMethod.GET, "/v1/pricing/advisory-lines?date=" + tomorrow, SOCIETY, null);
        assertThat(advice.getBody()).hasSize(1);
        assertThat(advice.getBody().get(0).get("price").decimalValue()).isEqualByComparingTo("210");
    }

    @Test
    void resolveBasketWorksForRetailLines() {
        ResponseEntity<JsonNode> created = send(
                HttpMethod.POST, "/v1/pricing/lists", SOCIETY, Map.of("kind", "RETAIL", "name", "Shelf prices basket"));
        String listId = created.getBody().get("priceListId").asText();

        send(
                HttpMethod.PUT,
                "/v1/pricing/lists/" + listId + "/lines",
                SOCIETY,
                Map.of("lines", List.of(Map.of("skuId", rice, "uomCode", "EA", "tierFromQty", 0, "price", 185))));
        send(
                HttpMethod.POST,
                "/v1/pricing/lists/" + listId + "/publish",
                SOCIETY,
                Map.of("applyFrom", tomorrow.toString()));

        Map<String, Object> request = Map.of(
                "locationId", SHOP.toString(),
                "businessDate", tomorrow.toString(),
                "tenderKind", "CASH",
                "lines", List.of(Map.of("skuId", rice.toString(), "uom", "EA", "qty", 2.0)));

        ResponseEntity<JsonNode> resolved = send(HttpMethod.POST, "/v1/pricing/resolve/retail", SOCIETY, request);

        assertThat(resolved.getStatusCode())
                .as(String.valueOf(resolved.getBody()))
                .isEqualTo(HttpStatus.OK);
        JsonNode body = resolved.getBody();
        assertThat(body.get("lines").size()).isEqualTo(1);
        JsonNode lineResult = body.get("lines").get(0);
        assertThat(lineResult.get("skuId").asText()).isEqualTo(rice.toString());
        assertThat(lineResult.get("unitPrice").decimalValue()).isEqualByComparingTo("185");
        assertThat(lineResult.get("lineTotal").decimalValue()).isEqualByComparingTo("370");
        assertThat(body.get("total").decimalValue()).isEqualByComparingTo("370");
        assertThat(lineResult.get("capReason").asText()).isEqualTo("NONE");
        // Central has no snapshot (M3R-03).
        assertThat(body.get("snapshotVersion") == null
                        || body.get("snapshotVersion").isNull())
                .isTrue();
    }

    @Test
    void theBasketIsCappedByAControlPriceAndRefusesWhatTheEngineCannotPrice() {
        // The list is published first at 240; a gazette of 220 lands after it (M3R-01).
        ResponseEntity<JsonNode> created = send(
                HttpMethod.POST,
                "/v1/pricing/lists",
                SOCIETY,
                Map.of("kind", "RETAIL", "name", "Shelf before gazette"));
        String listId = created.getBody().get("priceListId").asText();
        send(
                HttpMethod.PUT,
                "/v1/pricing/lists/" + listId + "/lines",
                SOCIETY,
                Map.of("lines", List.of(Map.of("skuId", rice, "uomCode", "EA", "tierFromQty", 0, "price", 240))));
        assertThat(send(
                                HttpMethod.POST,
                                "/v1/pricing/lists/" + listId + "/publish",
                                SOCIETY,
                                Map.of("applyFrom", tomorrow.toString()))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(send(HttpMethod.POST, "/v1/pricing/control-prices", FEDERATION, gazette("220"))
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();

        ResponseEntity<JsonNode> khata =
                send(HttpMethod.POST, "/v1/pricing/resolve/retail", SOCIETY, basket("KHATA", 1.5));
        assertThat(khata.getStatusCode()).as(String.valueOf(khata.getBody())).isEqualTo(HttpStatus.OK);
        JsonNode line = khata.getBody().get("lines").get(0);
        assertThat(line.get("unitPrice").decimalValue()).isEqualByComparingTo("220");
        assertThat(line.get("controlPriceApplied").decimalValue()).isEqualByComparingTo("220");
        assertThat(line.get("capReason").asText()).isEqualTo("CONTROL_PRICE");
        // KHATA is the engine's ACCOUNT: exact, no cash rounding (M3R-02).
        assertThat(khata.getBody().get("rounding").decimalValue()).isEqualByComparingTo("0");

        ResponseEntity<JsonNode> voucher =
                send(HttpMethod.POST, "/v1/pricing/resolve/retail", SOCIETY, basket("VOUCHER", 1.0));
        assertThat(voucher.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(voucher.getBody().get("code").asText()).isEqualTo("m3.basket.tender_not_priced");

        ResponseEntity<JsonNode> fourDecimals =
                send(HttpMethod.POST, "/v1/pricing/resolve/retail", SOCIETY, basket("CASH", 1.0005));
        assertThat(fourDecimals.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fourDecimals.getBody().get("code").asText()).isEqualTo("m3.basket.qty_precision");
    }

    private Map<String, Object> basket(String tender, double qty) {
        return Map.of(
                "locationId", SHOP.toString(),
                "businessDate", tomorrow.toString(),
                "tenderKind", tender,
                "lines", List.of(Map.of("skuId", rice.toString(), "uom", "EA", "qty", qty)));
    }

    @Test
    void reviewsEndpointReturnsReviewsForAuthorizedEntity() {
        // Insert a review directly into the audit log
        superuserJdbc()
                .update(
                        "insert into kernel.audit_event (audit_id, event_type_code, occurred_at, owner_entity_id, subject_table, subject_id, correlation_id, after_state) "
                                + "values (?, 'BATCH_MRP_BELOW_LIST', now(), ?, 'sku', ?, ?, ?::jsonb)",
                        Ids.next(),
                        SOCIETY,
                        rice,
                        Ids.next(),
                        "{\"batchId\":\"" + Ids.next() + "\", \"printedMrp\": 90, \"listPrice\": 95}");

        ResponseEntity<JsonNode> societyReviews = send(HttpMethod.GET, "/v1/pricing/reviews", SOCIETY, null);
        assertThat(societyReviews.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(societyReviews.getBody()).hasSize(1);
        assertThat(societyReviews.getBody().get(0).get("kind").asText()).isEqualTo("BATCH_MRP_BELOW_LIST");
        // M3R-07: the owner, the SKU and the status are filled.
        assertThat(societyReviews.getBody().get(0).get("ownerEntityId").asText())
                .isEqualTo(SOCIETY.toString());
        assertThat(societyReviews.getBody().get(0).get("skuCode").asText()).isEqualTo("M3H-RICE");
        assertThat(societyReviews.getBody().get(0).get("status").asText()).isEqualTo("OPEN");
        assertThat(send(HttpMethod.GET, "/v1/pricing/reviews?status=SETTLED", SOCIETY, null)
                        .getBody())
                .isEmpty();
        assertThat(societyReviews
                        .getBody()
                        .get(0)
                        .get("details")
                        .get("printedMrp")
                        .decimalValue())
                .isEqualByComparingTo("90");

        ResponseEntity<JsonNode> federationReviews = send(HttpMethod.GET, "/v1/pricing/reviews", FEDERATION, null);
        assertThat(federationReviews.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(federationReviews.getBody()).isEmpty(); // unrelated entity cannot see them

        ResponseEntity<JsonNode> filteredReviews =
                send(HttpMethod.GET, "/v1/pricing/reviews?kind=TRADE_ABOVE_MRP", SOCIETY, null);
        assertThat(filteredReviews.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(filteredReviews.getBody()).isEmpty(); // filtered out
    }

    private Map<String, Object> gazette(String ceiling) {
        return Map.of(
                "skuId",
                rice,
                "ceilingPrice",
                Double.valueOf(ceiling),
                "ceilingUomCode",
                "EA",
                "effectiveFrom",
                yesterday.toString(),
                "gazetteReference",
                "2492/29");
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
