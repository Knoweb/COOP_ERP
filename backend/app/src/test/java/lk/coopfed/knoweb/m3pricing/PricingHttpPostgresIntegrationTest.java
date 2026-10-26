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
    private static final UUID ADOPTING_SOCIETY = UUID.fromString("0190e740-0000-7000-8000-000000000005");

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
        admin.update(
                "insert into party.entity "
                        + "(entity_id, entity_code, entity_type, legal_name_en, status) "
                        + "values (?, 'M3HTTPADOPT', 'MPCS', 'HTTP adoption society', 'ACTIVE')",
                ADOPTING_SOCIETY);

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
        admin.update("delete from party.entity_party_directory where entity_id = ?", ADOPTING_SOCIETY);
        admin.update("delete from party.entity where entity_id = ?", ADOPTING_SOCIETY);
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
    void aDraftRuleCanBeAmendedThroughHttp() {
        ResponseEntity<JsonNode> authored = send(
                HttpMethod.POST,
                "/v1/pricing/rules",
                DISTRIBUTOR,
                Map.of(
                        "name", "Original rice promotion",
                        "kind", "TIME_LIMITED_PRICE",
                        "predicate", Map.of("skuId", rice),
                        "benefit", Map.of("kind", "PERCENT_OFF", "value", 10),
                        "validFrom", applyFrom.toString()));

        assertThat(authored.getStatusCode())
                .as(String.valueOf(authored.getBody()))
                .isEqualTo(HttpStatus.CREATED);

        String ruleId = authored.getBody().get("ruleId").asText();
        String path = "/v1/pricing/rules/" + ruleId;

        Map<String, Object> amendment = Map.of(
                "name", "Updated rice promotion",
                "kind", "TIME_LIMITED_PRICE",
                "predicate", Map.of("skuId", rice),
                "benefit", Map.of("kind", "PERCENT_OFF", "value", 20),
                "priority", 25,
                "validFrom", applyFrom.toString(),
                "validTo", applyFrom.plusDays(7).toString());

        ResponseEntity<JsonNode> amended = send(HttpMethod.PATCH, path, DISTRIBUTOR, amendment);

        assertThat(amended.getStatusCode())
                .as(String.valueOf(amended.getBody()))
                .isEqualTo(HttpStatus.OK);

        JsonNode updated = amended.getBody();

        assertThat(updated.get("ruleId").asText()).isEqualTo(ruleId);
        assertThat(updated.get("name").asText()).isEqualTo("Updated rice promotion");
        assertThat(updated.get("status").asText()).isEqualTo("DRAFT");
        assertThat(updated.get("priority").asInt()).isEqualTo(25);
        assertThat(updated.get("benefit").get("value").decimalValue()).isEqualByComparingTo("20");
        assertThat(updated.get("validTo").asText())
                .isEqualTo(applyFrom.plusDays(7).toString());

        // Another entity cannot amend this rule.
        ResponseEntity<JsonNode> otherOwner = send(HttpMethod.PATCH, path, FEDERATION, amendment);

        assertThat(otherOwner.getStatusCode())
                .as(String.valueOf(otherOwner.getBody()))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(otherOwner.getBody().get("code").asText()).isEqualTo("m3.rule.not_found");

        // Activate the rule before testing draft-only protection.
        ResponseEntity<JsonNode> activated = send(HttpMethod.POST, path + "/activate", DISTRIBUTOR, null);

        assertThat(activated.getStatusCode())
                .as(String.valueOf(activated.getBody()))
                .isEqualTo(HttpStatus.OK);

        // ACTIVE rules must not be amended.
        ResponseEntity<JsonNode> activeAmendment = send(HttpMethod.PATCH, path, DISTRIBUTOR, amendment);

        assertThat(activeAmendment.getStatusCode())
                .as(String.valueOf(activeAmendment.getBody()))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(activeAmendment.getBody().get("code").asText()).isEqualTo("m3.rule.not_draft");

        ResponseEntity<JsonNode> finalRule = send(HttpMethod.GET, path, DISTRIBUTOR, null);

        assertThat(finalRule.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(finalRule.getBody().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(finalRule.getBody().get("name").asText()).isEqualTo("Updated rice promotion");
    }

    @Test
    void adoptionRefusesANonMpcsEntityThroughHttp() {
        ResponseEntity<JsonNode> authored = send(
                HttpMethod.POST,
                "/v1/pricing/rules",
                FEDERATION,
                Map.of(
                        "name", "Ordinary Federation promotion",
                        "kind", "TIME_LIMITED_PRICE",
                        "predicate", Map.of("skuId", rice),
                        "benefit", Map.of("kind", "PERCENT_OFF", "value", 10),
                        "validFrom", applyFrom.toString()));

        assertThat(authored.getStatusCode())
                .as(String.valueOf(authored.getBody()))
                .isEqualTo(HttpStatus.CREATED);

        String sourceRuleId = authored.getBody().get("ruleId").asText();

        ResponseEntity<JsonNode> adopted =
                send(HttpMethod.POST, "/v1/pricing/rules/" + sourceRuleId + "/adopt", DISTRIBUTOR, null);

        assertThat(adopted.getStatusCode())
                .as(String.valueOf(adopted.getBody()))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(adopted.getBody().get("code").asText()).isEqualTo("m3.rule.adopt_mpcs_only");
    }

    @Test
    void anActiveFederationAdvisoryCanBeAdoptedThroughHttp() {
        ResponseEntity<JsonNode> authored = send(
                HttpMethod.POST,
                "/v1/pricing/rules",
                FEDERATION,
                Map.of(
                        "name",
                        "Federation rice advisory",
                        "kind",
                        "TIME_LIMITED_PRICE",
                        "predicate",
                        Map.of("skuId", rice),
                        "benefit",
                        Map.of("kind", "PERCENT_OFF", "value", 10),
                        "validFrom",
                        applyFrom.toString(),
                        "advisory",
                        true));

        assertThat(authored.getStatusCode())
                .as(String.valueOf(authored.getBody()))
                .isEqualTo(HttpStatus.CREATED);

        UUID sourceId = UUID.fromString(authored.getBody().get("ruleId").asText());

        ResponseEntity<JsonNode> activated =
                send(HttpMethod.POST, "/v1/pricing/rules/" + sourceId + "/activate", FEDERATION, null);

        assertThat(activated.getStatusCode())
                .as(String.valueOf(activated.getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(superuserJdbc()
                        .queryForMap(
                                "select owner_entity_id, advisory, status "
                                        + "from pricing.discount_rule where rule_id = ?",
                                sourceId))
                .containsEntry("owner_entity_id", FEDERATION)
                .containsEntry("advisory", true)
                .containsEntry("status", "ACTIVE");

        ResponseEntity<JsonNode> adopted =
                send(HttpMethod.POST, "/v1/pricing/rules/" + sourceId + "/adopt", ADOPTING_SOCIETY, null);

        assertThat(adopted.getStatusCode())
                .as(String.valueOf(adopted.getBody()))
                .isEqualTo(HttpStatus.CREATED);

        String newId = adopted.getBody().get("ruleId").asText();

        assertThat(newId).isNotEqualTo(sourceId.toString());
        assertThat(adopted.getBody().get("status").asText()).isEqualTo("DRAFT");
        assertThat(adopted.getHeaders().getLocation()).hasToString("/v1/pricing/rules/" + newId);

        Integer count = superuserJdbc()
                .queryForObject(
                        "select count(*) from pricing.discount_rule "
                                + "where rule_id = ?::uuid "
                                + "and owner_entity_id = ? "
                                + "and adopted_from_rule_id = ?",
                        Integer.class,
                        newId,
                        ADOPTING_SOCIETY,
                        sourceId);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void aNonFederationEntityCannotAuthorAnAdvisoryRule() {
        ResponseEntity<JsonNode> response = send(
                HttpMethod.POST,
                "/v1/pricing/rules",
                DISTRIBUTOR,
                Map.of(
                        "name",
                        "Unauthorized advisory",
                        "kind",
                        "TIME_LIMITED_PRICE",
                        "predicate",
                        Map.of("skuId", rice),
                        "benefit",
                        Map.of("kind", "PERCENT_OFF", "value", 10),
                        "validFrom",
                        applyFrom.toString(),
                        "advisory",
                        true));

        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(response.getBody().get("code").asText()).isEqualTo("m3.rule.advisory_federation_only");

        Integer count = superuserJdbc()
                .queryForObject(
                        "select count(*) from pricing.discount_rule " + "where owner_entity_id = ? and advisory = true",
                        Integer.class,
                        DISTRIBUTOR);

        assertThat(count).isZero();
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
