package lk.coopfed.knoweb.m4trading;

import static lk.coopfed.knoweb.m4trading.OrderHttpPostgresIntegrationTest.headers;
import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER_USER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER_USER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SHOP;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.m4trading.query.OrderView;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
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
import org.springframework.http.ResponseEntity;

/** The delivery note operations of the slice (24A section 5) over HTTP: draft, issue, dispatch, read by both parties. */
@Import(TradingFlow.class)
class DeliveryHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    TradingFlow flow;

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    @Test
    void aDeliveryNoteIsDraftedIssuedDispatchedAndSeenByTheBuyer() {
        OrderView order = flow.acceptedOrder();
        List<Map<String, Object>> lines = order.lines().stream()
                .map(line ->
                        Map.<String, Object>of("orderLineId", line.lineId().toString(), "qty", line.allocatedQty()))
                .toList();

        ResponseEntity<JsonNode> created = post(
                "/v1/trading/delivery-notes",
                Map.of(
                        "vehicleRef",
                        "WP-1234",
                        "driverName",
                        "Sunil",
                        "drops",
                        List.of(Map.of(
                                "shipToLocationId",
                                SHOP.toString(),
                                "billToEntityId",
                                BUYER.toString(),
                                "lines",
                                lines))),
                headers(SELLER_USER, SELLER));
        assertThat(created.getStatusCode())
                .as(String.valueOf(created.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("status").asText()).isEqualTo("DRAFT");
        String noteId = created.getBody().get("deliveryNoteId").asText();

        ResponseEntity<JsonNode> issued =
                post("/v1/trading/delivery-notes/" + noteId + "/issue", null, headers(SELLER_USER, SELLER));
        assertThat(issued.getStatusCode()).as(String.valueOf(issued.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(issued.getBody().get("docNumber").asText()).isEqualTo("D4S-DN-0000001");

        ResponseEntity<JsonNode> dispatched =
                post("/v1/trading/delivery-notes/" + noteId + "/dispatch", Map.of(), headers(SELLER_USER, SELLER));
        assertThat(dispatched.getStatusCode())
                .as(String.valueOf(dispatched.getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(dispatched.getBody().get("status").asText()).isEqualTo("IN_TRANSIT");

        ResponseEntity<JsonNode> asBuyer = http.exchange(
                "/v1/trading/delivery-notes?role=BUYER",
                HttpMethod.GET,
                new HttpEntity<>(headers(BUYER_USER, BUYER)),
                JsonNode.class);
        assertThat(asBuyer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asBuyer.getBody()).hasSize(1);
        assertThat(asBuyer.getBody().get(0).get("drops").get(0).get("lines")).hasSize(2);

        ResponseEntity<JsonNode> missing = http.exchange(
                "/v1/trading/delivery-notes/" + UUID.randomUUID(),
                HttpMethod.GET,
                new HttpEntity<>(headers(BUYER_USER, BUYER)),
                JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<JsonNode> post(String path, Object body, HttpHeaders headers) {
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
