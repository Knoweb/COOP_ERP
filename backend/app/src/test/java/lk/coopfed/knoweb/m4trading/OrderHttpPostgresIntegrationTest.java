package lk.coopfed.knoweb.m4trading;

import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER_USER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER_USER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.WAREHOUSE;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
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

/** The order operations of the slice (24A section 5) over HTTP: draft, submit, read by both parties, cancel, availability. */
class OrderHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    @Test
    void anOrderIsDraftedSubmittedReadByTheSellerAndCancelledByTheBuyer() {
        ResponseEntity<JsonNode> created = post(
                "/v1/trading/orders",
                Map.of(
                        "sellerEntityId",
                        SELLER.toString(),
                        "requestedEta",
                        TradingFixture.today().plusDays(2).toString(),
                        "deliverToLocationId",
                        WAREHOUSE.toString(),
                        "lines",
                        List.of(Map.of("skuId", RICE.toString(), "uomCode", "EA", "qty", 12))),
                headers(BUYER_USER, BUYER));
        assertThat(created.getStatusCode())
                .as(String.valueOf(created.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("status").asText()).isEqualTo("DRAFT");
        String orderId = created.getBody().get("orderId").asText();

        ResponseEntity<JsonNode> submitted =
                post("/v1/trading/orders/" + orderId + "/submit", null, headers(BUYER_USER, BUYER));
        assertThat(submitted.getStatusCode())
                .as(String.valueOf(submitted.getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(submitted.getBody().get("docNumber").asText()).isEqualTo("D4B-ORD-0000001");

        ResponseEntity<JsonNode> asSeller = http.exchange(
                "/v1/trading/orders?role=SELLER",
                HttpMethod.GET,
                new HttpEntity<>(headers(SELLER_USER, SELLER)),
                JsonNode.class);
        assertThat(asSeller.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asSeller.getBody()).hasSize(1);
        assertThat(asSeller.getBody().get(0).get("status").asText()).isEqualTo("SUBMITTED");
        // The seller reads the buyer's delivery point from the order (DeliveryPointResponse, V0004).
        assertThat(asSeller.getBody().get(0).get("deliverTo").get("code").asText())
                .isEqualTo("W1");
        assertThat(asSeller.getBody().get(0).get("deliverTo").get("nameEn").asText())
                .isEqualTo("W1");

        ResponseEntity<JsonNode> availability = http.exchange(
                "/v1/trading/orders/availability?sellerId=" + SELLER + "&skuIds=" + RICE,
                HttpMethod.GET,
                new HttpEntity<>(headers(SELLER_USER, SELLER)),
                JsonNode.class);
        assertThat(availability.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(availability.getBody().get(0).get("availableQty").decimalValue())
                .isPositive();

        ResponseEntity<JsonNode> cancelled = post(
                "/v1/trading/orders/" + orderId + "/cancel",
                Map.of("reasonCode", "CHANGED_MIND"),
                headers(BUYER_USER, BUYER));
        assertThat(cancelled.getStatusCode())
                .as(String.valueOf(cancelled.getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("status").asText()).isEqualTo("CANCELLED");

        ResponseEntity<JsonNode> missing = http.exchange(
                "/v1/trading/orders/" + UUID.randomUUID(),
                HttpMethod.GET,
                new HttpEntity<>(headers(BUYER_USER, BUYER)),
                JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().get("code").asText()).isEqualTo("m4.order.not_found");

        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .contains("ORDER_CREATED", "ORDER_SUBMITTED", "ORDER_CANCELLED");
    }

    @Test
    void theSellerAcceptsOneOrderAndRejectsAnother() {
        String first = submittedOrder();
        String second = submittedOrder();

        ResponseEntity<JsonNode> accepted = post(
                "/v1/trading/orders/" + first + "/accept",
                Map.of("committedEta", TradingFixture.today().plusDays(2).toString()),
                headers(SELLER_USER, SELLER));
        assertThat(accepted.getStatusCode())
                .as(String.valueOf(accepted.getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(accepted.getBody().get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(accepted.getBody().get("lines").get(0).get("allocatedQty").decimalValue())
                .isEqualByComparingTo("12");

        ResponseEntity<JsonNode> rejected = post(
                "/v1/trading/orders/" + second + "/reject",
                Map.of("reasonCode", "NO_STOCK"),
                headers(SELLER_USER, SELLER));
        assertThat(rejected.getStatusCode())
                .as(String.valueOf(rejected.getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(rejected.getBody().get("status").asText()).isEqualTo("REJECTED");
    }

    @Test
    void theBuyerAmendsASubmittedOrderAndTheAnswerIsTheNextVersion() {
        String first = submittedOrder();

        ResponseEntity<JsonNode> amended = post(
                "/v1/trading/orders/" + first + "/amend",
                Map.of(
                        "reason",
                        "less rice please",
                        "requestedEta",
                        TradingFixture.today().plusDays(4).toString(),
                        "lines",
                        List.of(Map.of("skuId", RICE.toString(), "qty", 7))),
                headers(BUYER_USER, BUYER));
        assertThat(amended.getStatusCode())
                .as(String.valueOf(amended.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        assertThat(amended.getBody().get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(amended.getBody().get("version").asInt()).isEqualTo(2);
        assertThat(amended.getBody().get("amendsOrderId").asText()).isEqualTo(first);
        assertThat(amended.getBody().get("lines").get(0).get("requestedQty").decimalValue())
                .isEqualByComparingTo("7");

        ResponseEntity<JsonNode> old = http.exchange(
                "/v1/trading/orders/" + first,
                HttpMethod.GET,
                new HttpEntity<>(headers(BUYER_USER, BUYER)),
                JsonNode.class);
        assertThat(old.getBody().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(old.getBody().get("amendedByOrderId").asText())
                .isEqualTo(amended.getBody().get("orderId").asText());

        ResponseEntity<JsonNode> again = post(
                "/v1/trading/orders/" + first + "/amend",
                Map.of("reason", "another reason", "lines", List.of(Map.of("skuId", RICE.toString(), "qty", 7))),
                headers(BUYER_USER, BUYER));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(again.getBody().get("code").asText()).isEqualTo("m4.order.not_amendable");
        ResponseEntity<JsonNode> noLines = post(
                "/v1/trading/orders/" + first + "/amend",
                Map.of("reason", "another reason", "lines", List.of()),
                headers(BUYER_USER, BUYER));
        assertThat(noLines.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private String submittedOrder() {
        ResponseEntity<JsonNode> created = post(
                "/v1/trading/orders",
                Map.of(
                        "sellerEntityId",
                        SELLER.toString(),
                        "lines",
                        List.of(Map.of("skuId", RICE.toString(), "qty", 12))),
                headers(BUYER_USER, BUYER));
        String orderId = created.getBody().get("orderId").asText();
        post("/v1/trading/orders/" + orderId + "/submit", null, headers(BUYER_USER, BUYER));
        return orderId;
    }

    @Test
    void aBrokenRuleIsAProblemDocument() {
        ResponseEntity<JsonNode> refused = post(
                "/v1/trading/orders",
                Map.of(
                        "sellerEntityId",
                        BUYER.toString(),
                        "lines",
                        List.of(Map.of("skuId", RICE.toString(), "qty", 1))),
                headers(BUYER_USER, BUYER));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("m4.order.seller_is_buyer");
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
}
