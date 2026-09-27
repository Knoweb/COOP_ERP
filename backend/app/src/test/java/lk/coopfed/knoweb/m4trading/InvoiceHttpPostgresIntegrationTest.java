package lk.coopfed.knoweb.m4trading;

import static lk.coopfed.knoweb.m4trading.OrderHttpPostgresIntegrationTest.headers;
import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER_USER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.DHAL;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER_USER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SHOP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.m4trading.api.CaptureGrn;
import lk.coopfed.knoweb.m4trading.api.ConfirmGrn;
import lk.coopfed.knoweb.m4trading.internal.grn.CaptureGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.grn.ConfirmGrnHandler;
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

/** The invoice operations of the slice (24A section 5) over HTTP: issue from a confirmed GRN, read by both parties. */
@Import(TradingFlow.class)
class InvoiceHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    TradingFlow flow;

    @Autowired
    CaptureGrnHandler capture;

    @Autowired
    ConfirmGrnHandler confirm;

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
    void theSellerIssuesAnInvoiceAndTheBuyerReadsIt() {
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
                                        RICE, "EA", new BigDecimal("10"), BigDecimal.ZERO, null, null, null, null),
                                new CaptureGrn.Line(
                                        DHAL, "EA", new BigDecimal("4"), BigDecimal.ZERO, null, null, null, null))),
                buyer());
        confirm.handle(new ConfirmGrn(grnId), buyer());

        HttpHeaders sellerHeaders = headers(SELLER_USER, SELLER);
        sellerHeaders.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> issued = http.exchange(
                "/v1/trading/invoices",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("grnIds", List.of(grnId.toString())), sellerHeaders),
                JsonNode.class);
        assertThat(issued.getStatusCode()).as(String.valueOf(issued.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat(issued.getBody().get("grossAmount").decimalValue()).isEqualByComparingTo("1793.60");
        String invoiceId = issued.getBody().get("invoiceId").asText();

        ResponseEntity<JsonNode> asBuyer = http.exchange(
                "/v1/trading/invoices/" + invoiceId,
                HttpMethod.GET,
                new HttpEntity<>(headers(BUYER_USER, BUYER)),
                JsonNode.class);
        assertThat(asBuyer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asBuyer.getBody().get("docNumber").asText()).isEqualTo("D4S-INV-0000001");
        assertThat(asBuyer.getBody().get("lines")).hasSize(2);

        // Nothing printed yet (no worker in this test): the Print link is not ready (M4-11).
        ResponseEntity<JsonNode> print = http.exchange(
                "/v1/trading/invoices/" + invoiceId + "/print",
                HttpMethod.GET,
                new HttpEntity<>(headers(SELLER_USER, SELLER)),
                JsonNode.class);
        assertThat(print.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(String.valueOf(print.getBody())).contains("m4.invoice.print_not_ready");
    }
}
