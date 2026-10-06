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
 * The contract of openapi/m3pricing.yaml, operation by operation, through HTTP as the generated
 * client calls it: create, set lines (per-line outcomes), publish, get with lines, list, draft a
 * new version, and the trade price of an order line; a refused rule is a 422 problem, a list
 * another entity cannot see a 404.
 */
@Import(PinnedClock.class)
class PricingHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID DISTRIBUTOR = UUID.fromString("0190e740-0000-7000-8000-000000000003");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e740-0000-7000-8000-000000000100");
    private static final UUID RELATIONSHIP = UUID.fromString("0190e740-0000-7000-8000-000000000401");

    @Autowired
    TestRestTemplate http;

    private final UUID rice = Ids.next();
    // Tomorrow, not today: the test takes a while, and a list published "from today" is refused
    // as backdated once midnight in Colombo passes during the run (CI runs at any hour).
    private final LocalDate applyFrom = PinnedClock.TODAY.plusDays(1);

    @BeforeEach
    void arrange() {
        clean();
        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'Each', false) on conflict do nothing");
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)"
                        + " values (?, 'M3HTTP', 'M3 HTTP tax', ?) on conflict do nothing",
                TAX_CATEGORY,
                FEDERATION);
        admin.update(
                "insert into catalogue.sku (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si,"
                        + " short_name_ta, base_uom_code, sold_by_weight, tax_category_id)"
                        + " values (?, 'M3-HTTP-RICE', ?, 'SHARED', 'Rice', 'Rice', 'Rice', 'EA', false, ?)",
                rice,
                FEDERATION,
                TAX_CATEGORY);
        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table pricing.price_list_line, pricing.price_list cascade");
        admin.execute("truncate table pricing.discount_rule");
        admin.update("delete from party.entity_relationship where relationship_id = ?", RELATIONSHIP);
        admin.execute("truncate table catalogue.sku cascade");
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
    }

    @Test
    void everyOperationFollowsTheSlice() {
        ResponseEntity<JsonNode> created =
                send(HttpMethod.POST, "/v1/pricing/lists", FEDERATION, Map.of("kind", "TRADE", "name", "Uniform list"));
        assertThat(created.getStatusCode())
                .as(String.valueOf(created.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        String listId = created.getBody().get("priceListId").asText();
        assertThat(created.getBody().get("status").asText()).isEqualTo("DRAFT");
        assertThat(created.getHeaders().getLocation()).hasToString("/v1/pricing/lists/" + listId);

        ResponseEntity<JsonNode> refusedLines = send(
                HttpMethod.PUT,
                "/v1/pricing/lists/" + listId + "/lines",
                FEDERATION,
                Map.of("lines", List.of(Map.of("skuId", rice, "uomCode", "EA", "tierFromQty", 5, "price", 100))));
        assertThat(refusedLines.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refusedLines.getBody().get("saved").asBoolean()).isFalse();
        assertThat(refusedLines.getBody().get("outcomes").get(0).get("reason").asText())
                .isEqualTo("m3.price_list.line.tier_base_missing");

        ResponseEntity<JsonNode> lines = send(
                HttpMethod.PUT,
                "/v1/pricing/lists/" + listId + "/lines",
                FEDERATION,
                Map.of(
                        "lines",
                        List.of(
                                Map.of("skuId", rice, "uomCode", "EA", "tierFromQty", 0, "price", 100),
                                Map.of("skuId", rice, "uomCode", "EA", "tierFromQty", 10, "price", 95.5))));
        assertThat(lines.getBody().get("saved").asBoolean()).isTrue();
        assertThat(lines.getBody().get("outcomes")).hasSize(2);

        ResponseEntity<JsonNode> published = send(
                HttpMethod.POST,
                "/v1/pricing/lists/" + listId + "/publish",
                FEDERATION,
                Map.of("applyFrom", applyFrom.toString()));
        assertThat(published.getStatusCode())
                .as(String.valueOf(published.getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(published.getBody().get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published.getBody().get("applyFrom").asText()).isEqualTo(applyFrom.toString());

        ResponseEntity<JsonNode> detail = send(HttpMethod.GET, "/v1/pricing/lists/" + listId, FEDERATION, null);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody().get("list").get("version").asInt()).isEqualTo(1);
        assertThat(detail.getBody().get("lines")).hasSize(2);

        assertThat(send(HttpMethod.GET, "/v1/pricing/lists/" + listId, DISTRIBUTOR, null)
                        .getStatusCode())
                .as("no relationship binds the list yet: the distributor may not see it")
                .isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<JsonNode> list = send(HttpMethod.GET, "/v1/pricing/lists?kind=TRADE", FEDERATION, null);
        assertThat(list.getBody()).hasSize(1);

        ResponseEntity<JsonNode> again = send(
                HttpMethod.POST,
                "/v1/pricing/lists/" + listId + "/publish",
                FEDERATION,
                Map.of("applyFrom", applyFrom.toString()));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(again.getBody().get("code").asText()).isEqualTo("m3.price_list.not_draft");

        ResponseEntity<JsonNode> version =
                send(HttpMethod.POST, "/v1/pricing/lists/" + listId + "/versions", FEDERATION, null);
        assertThat(version.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(version.getBody().get("version").asInt()).isEqualTo(2);
        assertThat(version.getBody().get("rootPriceListId").asText()).isEqualTo(listId);

        superuserJdbc()
                .update(
                        "insert into party.entity_relationship (relationship_id, seller_entity_id, buyer_entity_id,"
                                + " price_list_id, status, effective_from) values (?, ?, ?, ?::uuid, 'ACTIVE', DATE '2026-01-01')",
                        RELATIONSHIP,
                        FEDERATION,
                        DISTRIBUTOR,
                        listId);
        ResponseEntity<JsonNode> price = send(
                HttpMethod.GET,
                "/v1/pricing/resolve/trade?relationshipId=" + RELATIONSHIP + "&skuId=" + rice + "&uom=EA&qty=12&date="
                        + applyFrom,
                DISTRIBUTOR,
                null);
        assertThat(price.getStatusCode()).as(String.valueOf(price.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(price.getBody().get("unitPrice").decimalValue()).isEqualByComparingTo("95.5");
        assertThat(price.getBody().get("tierFromQty").decimalValue()).isEqualByComparingTo("10");

        assertThat(send(
                                HttpMethod.GET,
                                "/v1/pricing/resolve/trade?relationshipId=" + RELATIONSHIP + "&skuId=" + Ids.next()
                                        + "&uom=EA&qty=1&date=" + applyFrom,
                                DISTRIBUTOR,
                                null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theRuleOperationsFollowTheSlice() {
        ResponseEntity<JsonNode> authored = send(
                HttpMethod.POST,
                "/v1/pricing/rules",
                DISTRIBUTOR,
                Map.of(
                        "name", "Rice week",
                        "kind", "TIME_LIMITED_PRICE",
                        "predicate", Map.of("skuId", rice),
                        "benefit", Map.of("kind", "PERCENT_OFF", "value", 10),
                        "validFrom", applyFrom.toString()));
        assertThat(authored.getStatusCode())
                .as(String.valueOf(authored.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        String ruleId = authored.getBody().get("ruleId").asText();
        assertThat(authored.getHeaders().getLocation()).hasToString("/v1/pricing/rules/" + ruleId);
        assertThat(authored.getBody().get("status").asText()).isEqualTo("DRAFT");
        assertThat(authored.getBody().get("priority").asInt()).isEqualTo(100);
        assertThat(authored.getBody().get("predicate").get("skuId").asText()).isEqualTo(rice.toString());

        ResponseEntity<JsonNode> activated =
                send(HttpMethod.POST, "/v1/pricing/rules/" + ruleId + "/activate", DISTRIBUTOR, null);
        assertThat(activated.getStatusCode())
                .as(String.valueOf(activated.getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(activated.getBody().get("status").asText()).isEqualTo("ACTIVE");

        ResponseEntity<JsonNode> list = send(HttpMethod.GET, "/v1/pricing/rules?status=ACTIVE", DISTRIBUTOR, null);
        assertThat(list.getBody()).hasSize(1);
        assertThat(send(HttpMethod.GET, "/v1/pricing/rules/" + ruleId, FEDERATION, null)
                        .getStatusCode())
                .as("another entity's rule")
                .isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<JsonNode> withdrawn = send(
                HttpMethod.POST,
                "/v1/pricing/rules/" + ruleId + "/withdraw",
                DISTRIBUTOR,
                Map.of("reason", "Ended early"));
        assertThat(withdrawn.getBody().get("status").asText()).isEqualTo("WITHDRAWN");

        ResponseEntity<JsonNode> again = send(
                HttpMethod.POST,
                "/v1/pricing/rules/" + ruleId + "/withdraw",
                DISTRIBUTOR,
                Map.of("reason", "Ended early"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(again.getBody().get("code").asText()).isEqualTo("m3.rule.not_active");

        ResponseEntity<JsonNode> freeItem = send(
                HttpMethod.POST,
                "/v1/pricing/rules",
                DISTRIBUTOR,
                Map.of(
                        "name", "Free",
                        "kind", "FREE_ITEM",
                        "predicate", Map.of("skuId", rice),
                        "benefit", Map.of("kind", "FREE_QTY", "value", 1),
                        "validFrom", applyFrom.toString()));
        assertThat(freeItem.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(freeItem.getBody().get("code").asText()).isEqualTo("m3.rule.kind_not_available");
    }

    @Test
    void aRequestOutsideTheSchemaIsRefusedBeforeTheHandler() {
        ResponseEntity<JsonNode> response =
                send(HttpMethod.POST, "/v1/pricing/lists", FEDERATION, Map.of("kind", "TRADE"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(kernel.committedAudit()).isEmpty();
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
