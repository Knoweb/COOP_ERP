package lk.coopfed.knoweb.m4trading;

import static lk.coopfed.knoweb.m4trading.OrderHttpPostgresIntegrationTest.headers;
import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER_USER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.DHAL;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER_USER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SHOP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.STRANGER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static lk.coopfed.knoweb.m4trading.TradingFixture.seller;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.m4trading.api.CaptureGrn;
import lk.coopfed.knoweb.m4trading.api.ConfirmGrn;
import lk.coopfed.knoweb.m4trading.api.IssueInvoice;
import lk.coopfed.knoweb.m4trading.internal.grn.CaptureGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.grn.ConfirmGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.invoice.IssueInvoiceHandler;
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

/**
 * The payment operations of the slice (M4-07, M4-09) over HTTP: the seller records a cheque
 * against its invoice, the invoice reads SETTLED with the payment for both parties, the cheque
 * bounces and the invoice is OPEN again; the exposures of both sides; the shape of a request is
 * refused by the kernel (400) and a stranger sees nothing.
 */
@Import(TradingFlow.class)
class PaymentHttpPostgresIntegrationTest extends PostgresIntegrationTest {

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

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    @Test
    void theSellerRecordsAChequeTheInvoiceSettlesAndABounceReopensIt() {
        UUID invoiceId = invoiced();

        ResponseEntity<JsonNode> recorded = post(
                "/v1/trading/payment-receipts",
                Map.of(
                        "buyerEntityId",
                        BUYER.toString(),
                        "method",
                        "CHEQUE",
                        "amount",
                        1736.00,
                        "reference",
                        "Paid at the counter",
                        "cheque",
                        Map.of(
                                "bank",
                                "Bank of Ceylon",
                                "chequeNo",
                                "400123",
                                "dated",
                                TradingFixture.today().toString()),
                        "settlements",
                        List.of(Map.of("invoiceId", invoiceId.toString(), "amount", 1736.00))));
        assertThat(recorded.getStatusCode())
                .as(String.valueOf(recorded.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        String receiptId = recorded.getBody().get("receiptId").asText();
        assertThat(recorded.getBody().get("docNumber").asText()).isEqualTo("D4S-PRC-0000001");
        assertThat(recorded.getBody().get("cheque").get("chequeNo").asText()).isEqualTo("400123");
        assertThat(recorded.getBody().get("allocations")).hasSize(1);

        JsonNode asBuyer =
                get("/v1/trading/invoices/" + invoiceId, BUYER_USER, BUYER).getBody();
        assertThat(asBuyer.get("paymentState").asText()).isEqualTo("SETTLED");
        assertThat(asBuyer.get("amountDue").decimalValue()).isEqualByComparingTo("0");
        assertThat(asBuyer.get("settledAmount").decimalValue()).isEqualByComparingTo("1736.00");
        assertThat(asBuyer.get("payments")).singleElement().satisfies(payment -> {
            assertThat(payment.get("receiptId").asText()).isEqualTo(receiptId);
            assertThat(payment.get("amount").decimalValue()).isEqualByComparingTo("1736.00");
        });
        JsonNode buyersList = get("/v1/trading/payment-receipts?role=BUYER", BUYER_USER, BUYER)
                .getBody();
        assertThat(buyersList)
                .singleElement()
                .satisfies(row -> assertThat(row.get("receiptId").asText()).isEqualTo(receiptId));

        // The buyer records nothing: it holds no bil.payment.record in this scope of the seller's receipt.
        ResponseEntity<JsonNode> buyerBounce = http.exchange(
                "/v1/trading/payment-receipts/" + receiptId + "/cheque-outcome",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("outcome", "BOUNCED"), withKey(headers(BUYER_USER, BUYER))),
                JsonNode.class);
        assertThat(buyerBounce.getStatusCode().is4xxClientError()).isTrue();

        ResponseEntity<JsonNode> bounced =
                post("/v1/trading/payment-receipts/" + receiptId + "/cheque-outcome", Map.of("outcome", "BOUNCED"));
        assertThat(bounced.getStatusCode())
                .as(String.valueOf(bounced.getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(bounced.getBody().get("status").asText()).isEqualTo("REVERSED");
        assertThat(bounced.getBody().get("cheque").get("outcome").asText()).isEqualTo("BOUNCED");
        assertThat(bounced.getBody().get("reversedBy").asText()).isNotBlank();

        JsonNode reopened =
                get("/v1/trading/invoices/" + invoiceId, BUYER_USER, BUYER).getBody();
        assertThat(reopened.get("paymentState").asText()).isEqualTo("OPEN");
        assertThat(reopened.get("amountDue").decimalValue()).isEqualByComparingTo("1736.00");
        assertThat(reopened.get("payments")).hasSize(2);

        // The exposure, as the seller and as the buyer: the invoice is open again.
        JsonNode sellers =
                get("/v1/trading/exposures?role=SELLER", SELLER_USER, SELLER).getBody();
        assertThat(sellers).singleElement().satisfies(row -> {
            assertThat(row.get("buyerEntityId").asText()).isEqualTo(BUYER.toString());
            assertThat(row.get("openInvoices").decimalValue()).isEqualByComparingTo("1736.00");
            assertThat(row.get("amount").decimalValue()).isEqualByComparingTo("1736.00");
        });
        JsonNode buyers =
                get("/v1/trading/exposures?role=BUYER", BUYER_USER, BUYER).getBody();
        assertThat(buyers)
                .singleElement()
                .satisfies(row -> assertThat(row.get("amount").decimalValue()).isEqualByComparingTo("1736.00"));

        // A stranger sees neither the receipt nor any exposure.
        ResponseEntity<JsonNode> stranger =
                get("/v1/trading/payment-receipts/" + receiptId, UUID.randomUUID(), STRANGER);
        // 422 like m4.creditnote.not_found: the kernel's status table (ProblemResponses) names only
        // the invoice's not_found as 404, and the kernel is not changed here.
        assertThat(stranger.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(String.valueOf(stranger.getBody())).contains("m4.payment.not_found");
        assertThat(get("/v1/trading/exposures?role=SELLER", UUID.randomUUID(), STRANGER)
                        .getBody())
                .isEmpty();
    }

    @Test
    void theShapeOfAPaymentIsTheSlicesToRefuse() {
        ResponseEntity<JsonNode> noAmount =
                post("/v1/trading/payment-receipts", Map.of("buyerEntityId", BUYER.toString(), "method", "CASH"));
        assertThat(noAmount.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<JsonNode> barter = post(
                "/v1/trading/payment-receipts",
                Map.of("buyerEntityId", BUYER.toString(), "method", "BARTER", "amount", 10));
        assertThat(barter.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<JsonNode> tooMuch = post(
                "/v1/trading/payment-receipts",
                Map.of(
                        "buyerEntityId",
                        BUYER.toString(),
                        "method",
                        "CASH",
                        "amount",
                        10,
                        "settlements",
                        List.of(Map.of("invoiceId", invoiced().toString(), "amount", 11))));
        assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(String.valueOf(tooMuch.getBody())).contains("m4.payment.exceeds_receipt");
    }

    private UUID invoiced() {
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
        return issueInvoice.handle(new IssueInvoice(List.of(grnId)), seller());
    }

    private ResponseEntity<JsonNode> post(String path, Object body) {
        return http.exchange(
                path, HttpMethod.POST, new HttpEntity<>(body, withKey(headers(SELLER_USER, SELLER))), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String path, UUID user, UUID entity) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(user, entity)), JsonNode.class);
    }

    private static HttpHeaders withKey(HttpHeaders headers) {
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return headers;
    }
}
