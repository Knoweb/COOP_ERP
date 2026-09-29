package lk.coopfed.knoweb.m4trading;

import static lk.coopfed.knoweb.m4trading.OrderHttpPostgresIntegrationTest.headers;
import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER_USER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.DHAL;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER_USER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SHOP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.WAREHOUSE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static lk.coopfed.knoweb.m4trading.TradingFixture.seller;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.internal.attachment.MemoryObjectStore;
import lk.coopfed.knoweb.m4trading.api.CaptureGrn;
import lk.coopfed.knoweb.m4trading.api.ConfirmGrn;
import lk.coopfed.knoweb.m4trading.api.IssueInvoice;
import lk.coopfed.knoweb.m4trading.internal.grn.CaptureGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.grn.ConfirmGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.invoice.IssueInvoiceHandler;
import lk.coopfed.knoweb.m4trading.query.DeliveryQueries;
import lk.coopfed.knoweb.m4trading.query.GrnQueries;
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

/** The claim and transfer request operations of the slice (M4-06, M4-10) over HTTP, as each party calls them. */
@Import({TradingFlow.class, MemoryObjectStore.class})
class ClaimAndTransferRequestHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    TradingFlow flow;

    @Autowired
    CaptureGrnHandler capture;

    @Autowired
    ConfirmGrnHandler confirm;

    @Autowired
    IssueInvoiceHandler issueInvoice;

    @Autowired
    DeliveryQueries deliveries;

    @Autowired
    GrnQueries grns;

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    @Test
    void theBuyerClaimsAndTheSellerApprovesWithACreditNote() {
        UUID noteId = flow.dispatchedNote(SHOP);
        UUID dropId = deliveries
                .getDeliveryNote(noteId, buyer())
                .orElseThrow()
                .drops()
                .get(0)
                .dropId();
        UUID grnId = capture.handle(
                new CaptureGrn(
                        dropId,
                        SHOP,
                        null,
                        List.of(
                                new CaptureGrn.Line(
                                        RICE, "EA", BigDecimal.TEN, BigDecimal.ZERO, null, null, null, null),
                                new CaptureGrn.Line(
                                        DHAL, "EA", new BigDecimal("4"), BigDecimal.ZERO, null, null, null, null))),
                buyer());
        confirm.handle(new ConfirmGrn(grnId), buyer());
        issueInvoice.handle(new IssueInvoice(List.of(grnId)), seller());
        UUID riceLine = grns.getGrn(grnId, buyer()).orElseThrow().lines().stream()
                .filter(line -> RICE.equals(line.skuId()))
                .findFirst()
                .orElseThrow()
                .lineId();

        ResponseEntity<JsonNode> raised = post(
                "/v1/trading/claims",
                BUYER_USER,
                BUYER,
                Map.of(
                        "grnId", grnId.toString(),
                        "kind", "EXPIRED_ON_ARRIVAL",
                        "lines", List.of(Map.of("grnLineId", riceLine.toString(), "qty", 1))));
        assertThat(raised.getStatusCode()).as(String.valueOf(raised.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat(raised.getBody().get("status").asText()).isEqualTo("RAISED");
        String claimId = raised.getBody().get("claimId").asText();

        // A request without lines is refused by the schema before any handler runs.
        ResponseEntity<JsonNode> empty = post(
                "/v1/trading/claims",
                BUYER_USER,
                BUYER,
                Map.of("grnId", grnId.toString(), "kind", "DAMAGED", "lines", List.of()));
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<JsonNode> listed = http.exchange(
                "/v1/trading/claims?role=SELLER",
                HttpMethod.GET,
                new HttpEntity<>(headers(SELLER_USER, SELLER)),
                JsonNode.class);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).hasSize(1);

        ResponseEntity<JsonNode> approved =
                post("/v1/trading/claims/" + claimId + "/approve", SELLER_USER, SELLER, Map.of("findings", "Expired"));
        assertThat(approved.getStatusCode())
                .as(String.valueOf(approved.getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.getBody().get("creditNoteDocNumber").asText()).isNotBlank();

        ResponseEntity<JsonNode> asBuyer = http.exchange(
                "/v1/trading/claims/" + claimId,
                HttpMethod.GET,
                new HttpEntity<>(headers(BUYER_USER, BUYER)),
                JsonNode.class);
        assertThat(asBuyer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asBuyer.getBody().get("lines").get(0).get("approvedQty").decimalValue())
                .isEqualByComparingTo("1");

        ResponseEntity<JsonNode> again =
                post("/v1/trading/claims/" + claimId + "/reject", SELLER_USER, SELLER, Map.of("reason", "late"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(String.valueOf(again.getBody())).contains("m4.claim.decided");
    }

    @Test
    void theShopAsksTheStoresAndTheSocietyApproves() {
        superuserJdbc()
                .update(
                        """
                        insert into inventory.stock_lot (stock_lot_id, owner_entity_id, location_id, batch_id, sku_id,
                            qty_on_hand, unit_cost, received_at)
                        values (?, ?, ?, ?, ?, 20, 90, now())
                        """,
                        UUID.randomUUID(),
                        BUYER,
                        WAREHOUSE,
                        UUID.randomUUID(),
                        RICE);
        HttpHeaders atTheShop = headers(BUYER_USER, BUYER);
        atTheShop.set("X-Scope-Location", SHOP.toString());
        atTheShop.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> asked = http.exchange(
                "/v1/trading/transfer-requests",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "fromLocationId",
                                WAREHOUSE.toString(),
                                "lines",
                                List.of(Map.of("skuId", RICE.toString(), "qty", 5))),
                        atTheShop),
                JsonNode.class);
        assertThat(asked.getStatusCode()).as(String.valueOf(asked.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat(asked.getBody().get("toLocationId").asText()).isEqualTo(SHOP.toString());
        String requestId = asked.getBody().get("requestId").asText();

        ResponseEntity<JsonNode> approved =
                post("/v1/trading/transfer-requests/" + requestId + "/approve", BUYER_USER, BUYER, Map.of());
        assertThat(approved.getStatusCode())
                .as(String.valueOf(approved.getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().get("status").asText()).isEqualTo("APPROVED");

        HttpHeaders readAtTheShop = headers(BUYER_USER, BUYER);
        readAtTheShop.set("X-Scope-Location", SHOP.toString());
        ResponseEntity<JsonNode> listed = http.exchange(
                "/v1/trading/transfer-requests", HttpMethod.GET, new HttpEntity<>(readAtTheShop), JsonNode.class);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).hasSize(1);
        assertThat(listed.getBody().get(0).get("status").asText()).isEqualTo("APPROVED");
    }

    private ResponseEntity<JsonNode> post(String path, UUID user, UUID entity, Object body) {
        HttpHeaders headers = headers(user, entity);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
