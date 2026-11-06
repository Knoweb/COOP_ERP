package lk.coopfed.knoweb.m4trading;

import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER_USER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SHOP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.STRANGER;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
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

/**
 * Manual Orders for Non-Computerized Branches (M3-10).
 * Verifies that:
 * 1. An authorized head-office user can successfully create an order on behalf of an allowed manual branch.
 * 2. Both target branch identity and acting operator user are preserved across database records, document history, and audit log.
 * 3. Attempts to create orders for unauthorized branches (belonging to another entity or outside caller scope) are refused by RLS and validation rules.
 */
class ManualBranchOrderPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MANUAL_BRANCH = UUID.fromString("0190f400-0000-7000-8000-000000000014");
    private static final UUID STRANGER_BRANCH = UUID.fromString("0190f400-0000-7000-8000-000000000015");

    @Autowired
    TestRestTemplate http;

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());

        // An allowed non-computerized / manual branch belonging to the buyer
        superuserJdbc()
                .update(
                        """
                insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, name_si, name_ta, status, connectivity_spec_met)
                values (?, ?, 'MB1', 'SHOP', 'Rural Manual Branch', 'ග්‍රාමීය අතින් ශාඛාව', 'கிராமிய கையேடு கிளை', 'ACTIVE', false)
                """,
                        MANUAL_BRANCH,
                        BUYER);

        // An unauthorized branch belonging to a different society / stranger entity
        superuserJdbc()
                .update(
                        """
                insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, name_si, name_ta, status, connectivity_spec_met)
                values (?, ?, 'SX1', 'SHOP', 'Stranger Branch', 'ආගන්තුක ශාඛාව', 'அந்நிய கிளை', 'ACTIVE', false)
                """,
                        STRANGER_BRANCH,
                        STRANGER);
    }

    @AfterEach
    void clean() {
        superuserJdbc()
                .update("delete from party.location where location_id in (?, ?)", MANUAL_BRANCH, STRANGER_BRANCH);
        TradingFixture.clean(superuserJdbc());
    }

    @Test
    void aHeadOfficeUserCanSuccessfullyCreateAnOrderForAnAllowedManualBranch() {
        ResponseEntity<JsonNode> created = post(
                "/v1/trading/orders",
                Map.of(
                        "sellerEntityId",
                        SELLER.toString(),
                        "requestedEta",
                        TradingFixture.today().plusDays(3).toString(),
                        "deliverToLocationId",
                        MANUAL_BRANCH.toString(),
                        "notes",
                        "Manual branch replenishment order",
                        "lines",
                        List.of(Map.of("skuId", RICE.toString(), "uomCode", "EA", "qty", 25))),
                headers(BUYER_USER, BUYER));

        assertThat(created.getStatusCode())
                .as(String.valueOf(created.getBody()))
                .isEqualTo(HttpStatus.CREATED);

        JsonNode body = created.getBody();
        assertThat(body.get("status").asText()).isEqualTo("DRAFT");
        assertThat(body.get("deliverToLocationId").asText()).isEqualTo(MANUAL_BRANCH.toString());
        assertThat(body.get("deliverTo").get("code").asText()).isEqualTo("MB1");
        assertThat(body.get("deliverTo").get("nameEn").asText()).isEqualTo("Rural Manual Branch");
        assertThat(body.get("operatorUserId").asText()).isEqualTo(BUYER_USER.toString());
        assertThat(body.get("notes").asText()).isEqualTo("Manual branch replenishment order");
        assertThat(body.get("lines")).hasSize(1);
    }

    @Test
    void auditAndHistoryRecordsClearlyShowBothTargetBranchAndActingUser() {
        ResponseEntity<JsonNode> created = post(
                "/v1/trading/orders",
                Map.of(
                        "sellerEntityId",
                        SELLER.toString(),
                        "requestedEta",
                        TradingFixture.today().plusDays(3).toString(),
                        "deliverToLocationId",
                        MANUAL_BRANCH.toString(),
                        "lines",
                        List.of(Map.of("skuId", RICE.toString(), "uomCode", "EA", "qty", 15))),
                headers(BUYER_USER, BUYER));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID orderId = UUID.fromString(created.getBody().get("orderId").asText());

        // 1. Kernel document base header preserves target branch and acting user
        Map<String, Object> doc = superuserJdbc()
                .queryForMap(
                        "select location_id, operator_user_id, owner_entity_id, counterparty_entity_id, status from kernel.document where document_id = ?",
                        orderId);
        assertThat(doc.get("location_id")).isEqualTo(MANUAL_BRANCH);
        assertThat(doc.get("operator_user_id")).isEqualTo(BUYER_USER);
        assertThat(doc.get("owner_entity_id")).isEqualTo(BUYER);
        assertThat(doc.get("counterparty_entity_id")).isEqualTo(SELLER);
        assertThat(doc.get("status")).isEqualTo("DRAFT");

        // 2. Trading doc_order preserves target delivery location snapshot
        Map<String, Object> order = superuserJdbc()
                .queryForMap(
                        "select deliver_to_location_id, deliver_to_code, deliver_to_name_en from trading.doc_order where document_id = ?",
                        orderId);
        assertThat(order.get("deliver_to_location_id")).isEqualTo(MANUAL_BRANCH);
        assertThat(order.get("deliver_to_code")).isEqualTo("MB1");
        assertThat(order.get("deliver_to_name_en")).isEqualTo("Rural Manual Branch");

        // 3. Audit trail preserves both acting user and target branch in after_state
        var createdAudit = kernel.committedAudit().stream()
                .filter(r -> "ORDER_CREATED".equals(r.eventType()))
                .findFirst()
                .orElseThrow();
        assertThat(createdAudit.scope().userId()).isEqualTo(BUYER_USER);
        assertThat(createdAudit.after())
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("deliverToLocationId", MANUAL_BRANCH)
                .containsEntry("branchLocationId", MANUAL_BRANCH)
                .containsEntry("actingUserId", BUYER_USER)
                .containsEntry("branchCode", "MB1")
                .containsEntry("manualBranch", true)
                .containsEntry("onBehalf", true);

        // Also verify the persisted audit table row
        Map<String, Object> auditDb = superuserJdbc()
                .queryForMap(
                        "select actor_user_id, subject_id from kernel.audit_event where event_type_code = 'ORDER_CREATED' and subject_id = ?",
                        orderId);
        assertThat(auditDb.get("actor_user_id")).isEqualTo(BUYER_USER);

        // 4. Submit order and verify document state history captures acting operator
        ResponseEntity<JsonNode> submitted =
                post("/v1/trading/orders/" + orderId + "/submit", null, headers(BUYER_USER, BUYER));
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> history = superuserJdbc()
                .queryForMap(
                        "select actor_user_id, from_status, to_status from kernel.document_state_history where document_id = ? and to_status = 'SUBMITTED'",
                        orderId);
        assertThat(history.get("actor_user_id")).isEqualTo(BUYER_USER);

        // Document header keeps the branch location unchanged
        UUID locationAfterSubmit = superuserJdbc()
                .queryForObject("select location_id from kernel.document where document_id = ?", UUID.class, orderId);
        assertThat(locationAfterSubmit).isEqualTo(MANUAL_BRANCH);
    }

    @Test
    void attemptsToCreateOrdersForUnauthorizedBranchesAreRefusedByRlsAndSecurity() {
        // Attempt 1: Head-office user attempts to create an order specifying a branch of another entity
        ResponseEntity<JsonNode> unauthorizedBranch = post(
                "/v1/trading/orders",
                Map.of(
                        "sellerEntityId",
                        SELLER.toString(),
                        "requestedEta",
                        TradingFixture.today().plusDays(3).toString(),
                        "deliverToLocationId",
                        STRANGER_BRANCH.toString(),
                        "lines",
                        List.of(Map.of("skuId", RICE.toString(), "qty", 10))),
                headers(BUYER_USER, BUYER));

        assertThat(unauthorizedBranch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(unauthorizedBranch.getBody().get("code").asText()).isEqualTo("m4.order.deliver_to_unknown");

        // Attempt 2: Head-office user attempts to create an order specifying a non-existent location ID
        ResponseEntity<JsonNode> nonExistentBranch = post(
                "/v1/trading/orders",
                Map.of(
                        "sellerEntityId",
                        SELLER.toString(),
                        "requestedEta",
                        TradingFixture.today().plusDays(3).toString(),
                        "deliverToLocationId",
                        UUID.randomUUID().toString(),
                        "lines",
                        List.of(Map.of("skuId", RICE.toString(), "qty", 10))),
                headers(BUYER_USER, BUYER));

        assertThat(nonExistentBranch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(nonExistentBranch.getBody().get("code").asText()).isEqualTo("m4.order.deliver_to_unknown");

        // Attempt 3: Caller operating with a location-scoped token (not entity-wide) is refused
        HttpHeaders locationScopedHeaders = locationHeaders(BUYER_USER, BUYER, SHOP);
        ResponseEntity<JsonNode> locationScopedCall = post(
                "/v1/trading/orders",
                Map.of(
                        "sellerEntityId",
                        SELLER.toString(),
                        "requestedEta",
                        TradingFixture.today().plusDays(3).toString(),
                        "deliverToLocationId",
                        MANUAL_BRANCH.toString(),
                        "lines",
                        List.of(Map.of("skuId", RICE.toString(), "qty", 10))),
                locationScopedHeaders);

        assertThat(locationScopedCall.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(locationScopedCall.getBody().get("code").asText()).isEqualTo("scope.invalid");
    }

    private ResponseEntity<JsonNode> post(String path, Object body, HttpHeaders headers) {
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    static HttpHeaders headers(UUID user, UUID entity) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityProvider.entityWideToken(user, entity));
        headers.set("X-Scope-Entity", entity.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    static HttpHeaders locationHeaders(UUID user, UUID entity, UUID location) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityProvider.token(
                user, entity, "OWN", claims -> claims.claim("scopes", List.of(entity + "/" + location))));
        headers.set("X-Scope-Entity", entity.toString());
        headers.set("X-Scope-Location", location.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
