package lk.coopfed.knoweb.m4trading;

import static lk.coopfed.knoweb.m4trading.OrderHttpPostgresIntegrationTest.headers;
import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER_USER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER_USER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SHOP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.m4trading.query.DeliveryQueries;
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

/** The GRN operations of the slice (24A section 5) over HTTP: capture, confirm with a short line, read by both parties. */
@Import(TradingFlow.class)
class GrnHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    TradingFlow flow;

    @Autowired
    DeliveryQueries deliveries;

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    @Test
    void theBuyerCapturesAndConfirmsAGrnAndTheSellerSeesIt() {
        UUID noteId = flow.dispatchedNote(SHOP);
        UUID dropId = deliveries
                .getDeliveryNote(noteId, buyer())
                .orElseThrow()
                .drops()
                .get(0)
                .dropId();

        ResponseEntity<JsonNode> captured = post(
                "/v1/trading/grns",
                Map.of(
                        "dropId",
                        dropId.toString(),
                        "locationId",
                        SHOP.toString(),
                        "lines",
                        List.of(Map.of("skuId", RICE.toString(), "receivedQty", 10))),
                headers(BUYER_USER, BUYER));
        assertThat(captured.getStatusCode())
                .as(String.valueOf(captured.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        assertThat(captured.getBody().get("status").asText()).isEqualTo("DRAFT");
        String grnId = captured.getBody().get("grnId").asText();

        ResponseEntity<JsonNode> confirmed =
                post("/v1/trading/grns/" + grnId + "/confirm", null, headers(BUYER_USER, BUYER));
        assertThat(confirmed.getStatusCode())
                .as(String.valueOf(confirmed.getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody().get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.getBody().get("discrepancyId").isNull()).isFalse(); // the dhal was not counted

        ResponseEntity<JsonNode> asSeller = http.exchange(
                "/v1/trading/grns?role=SELLER",
                HttpMethod.GET,
                new HttpEntity<>(headers(SELLER_USER, SELLER)),
                JsonNode.class);
        assertThat(asSeller.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asSeller.getBody()).hasSize(1);
        assertThat(asSeller.getBody().get(0).get("lines").get(0).get("batchId").asText())
                .isNotBlank();
    }

    private ResponseEntity<JsonNode> post(String path, Object body, HttpHeaders headers) {
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
