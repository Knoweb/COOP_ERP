package lk.coopfed.knoweb.m8reporting;

import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER_USER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.DHAL;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RELATIONSHIP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER_USER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SHOP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static lk.coopfed.knoweb.m4trading.TradingFixture.seller;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.internal.attachment.MemoryObjectStore;
import lk.coopfed.knoweb.m4trading.TradingFixture;
import lk.coopfed.knoweb.m4trading.TradingFlow;
import lk.coopfed.knoweb.m4trading.api.CaptureGrn;
import lk.coopfed.knoweb.m4trading.api.ConfirmGrn;
import lk.coopfed.knoweb.m4trading.api.IssueInvoice;
import lk.coopfed.knoweb.m4trading.internal.grn.CaptureGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.grn.ConfirmGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.invoice.IssueInvoiceHandler;
import lk.coopfed.knoweb.m4trading.query.DeliveryQueries;
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
import org.springframework.http.ResponseEntity;

@Import({TradingFlow.class, MemoryObjectStore.class})
class FinanceReportingHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private TradingFlow flow;

    @Autowired
    private CaptureGrnHandler capture;

    @Autowired
    private ConfirmGrnHandler confirm;

    @Autowired
    private IssueInvoiceHandler issue;

    @Autowired
    private DeliveryQueries deliveries;

    private UUID invoiceId;
    private UUID openingDocId;
    private UUID openingRecId;

    @BeforeEach
    void setUp() {
        TradingFixture.arrange(superuserJdbc());

        // 1. Arrange a real trading flow: Dispatch Note -> GRN -> Invoice
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

        invoiceId = issue.handle(new IssueInvoice(List.of(grnId)), seller());

        // 2. Arrange Opening Receivable Document
        openingDocId = Ids.next();
        openingRecId = Ids.next();
        superuserJdbc()
                .update(
                        """
                        INSERT INTO kernel.document (document_id, doc_type_code, doc_number_display, owner_entity_id,
                            counterparty_entity_id, status, business_date)
                        VALUES (?, 'OPB', 'D4S-OPB-0000001', ?, ?, 'ISSUED', ?)
                        """,
                        openingDocId,
                        SELLER,
                        BUYER,
                        TradingFixture.today());

        superuserJdbc()
                .update(
                        """
                        INSERT INTO trading.opening_receivable (opening_receivable_id, document_id, relationship_id,
                            seller_entity_id, buyer_entity_id, amount, reference_no, seller_signed_by, buyer_signed_by, notes)
                        VALUES (?, ?, ?, ?, ?, 75000.00, 'REF-OPEN-001', ?, ?, 'Initial opening balance')
                        """,
                        openingRecId,
                        openingDocId,
                        RELATIONSHIP,
                        SELLER,
                        BUYER,
                        SELLER_USER,
                        BUYER_USER);

        // 3. Arrange roles and permissions
        assignRole(SELLER_USER, SELLER, "rpt.report.run", "rpt.export.run");
        assignRole(BUYER_USER, BUYER, "rpt.report.run", "rpt.export.run");

        kernel.reset();
    }

    @AfterEach
    void tearDown() {
        TradingFixture.clean(superuserJdbc());
    }

    private void assignRole(UUID userId, UUID entityId, String... permissions) {
        UUID roleId = Ids.next();
        superuserJdbc()
                .update(
                        "INSERT INTO security.role (role_id, owner_entity_id, name_en, is_template, role_class, status)"
                                + " VALUES (?, ?, ?, false, 'OWN', 'ACTIVE')",
                        roleId,
                        entityId,
                        "Finance Test Role " + roleId);

        for (String perm : permissions) {
            superuserJdbc()
                    .update(
                            "INSERT INTO security.role_permission (role_id, permission_code) VALUES (?, ?)"
                                    + " ON CONFLICT DO NOTHING",
                            roleId,
                            perm);
        }

        superuserJdbc()
                .update(
                        "INSERT INTO security.app_user (user_id, home_entity_id, username, display_name, user_kind, status)"
                                + " VALUES (?, ?, ?, 'Finance Test User', 'BACK_OFFICE', 'ACTIVE') ON CONFLICT DO NOTHING",
                        userId,
                        entityId,
                        "u-" + userId);

        superuserJdbc()
                .update(
                        "INSERT INTO security.user_role (user_id, role_id, scope_entity_id, scope_location_id)"
                                + " VALUES (?, ?, ?, null)",
                        userId,
                        roleId,
                        entityId);
    }

    private HttpHeaders headers(UUID user, UUID entity) {
        return TestIdentityProvider.entityWideHeaders(user, entity);
    }

    @Test
    void debtorsReportAndCsvExport() {
        HttpHeaders sellerHeaders = headers(SELLER_USER, SELLER);

        // 1. JSON Report
        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/finance/debtors", HttpMethod.GET, new HttpEntity<>(sellerHeaders), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.has("items")).isTrue();
        assertThat(body.get("items").isArray()).isTrue();
        assertThat(body.get("totalOpenInvoices")).isNotNull();
        assertThat(body.get("totalNetExposure")).isNotNull();

        // 2. CSV Export
        ResponseEntity<String> csvResponse = http.exchange(
                "/v1/reporting/finance/debtors/csv", HttpMethod.GET, new HttpEntity<>(sellerHeaders), String.class);

        assertThat(csvResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csvResponse.getHeaders().getContentType().toString()).contains("text/csv");
        String csv = csvResponse.getBody();
        assertThat(csv).isNotNull();
        assertThat(csv)
                .contains(
                        "Debtor Code,Debtor Name,Credit Limit,Open Invoices,Accepted Not Invoiced,Unapplied Receipts,Unapplied Credits,Net Exposure");
    }

    @Test
    void creditorsReportAndCsvExport() {
        HttpHeaders buyerHeaders = headers(BUYER_USER, BUYER);

        // 1. JSON Report
        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/finance/creditors", HttpMethod.GET, new HttpEntity<>(buyerHeaders), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.has("items")).isTrue();
        assertThat(body.get("totalOpenInvoices")).isNotNull();
        assertThat(body.get("totalNetLiability")).isNotNull();

        // 2. CSV Export
        ResponseEntity<String> csvResponse = http.exchange(
                "/v1/reporting/finance/creditors/csv", HttpMethod.GET, new HttpEntity<>(buyerHeaders), String.class);

        assertThat(csvResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csvResponse.getHeaders().getContentType().toString()).contains("text/csv");
        String csv = csvResponse.getBody();
        assertThat(csv).isNotNull();
        assertThat(csv)
                .contains(
                        "Creditor Code,Creditor Name,Open Invoices,Accepted Not Invoiced,Unapplied Payments,Unapplied Credits,Net Liability");
    }

    @Test
    void openingBalancesReportAndCsvExport() {
        HttpHeaders sellerHeaders = headers(SELLER_USER, SELLER);

        // 1. JSON Report
        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/finance/opening-balances",
                HttpMethod.GET,
                new HttpEntity<>(sellerHeaders),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.has("items")).isTrue();
        assertThat(body.get("items").size()).isGreaterThanOrEqualTo(1);

        JsonNode first = body.get("items").get(0);
        assertThat(first.get("docNumberDisplay").asText()).isEqualTo("D4S-OPB-0000001");
        assertThat(first.get("role").asText()).isEqualTo("SELLER");
        assertThat(new BigDecimal(first.get("amount").asText())).isEqualByComparingTo("75000.00");
        assertThat(new BigDecimal(body.get("totalAmount").asText())).isGreaterThanOrEqualTo(new BigDecimal("75000.00"));

        // 2. CSV Export
        ResponseEntity<String> csvResponse = http.exchange(
                "/v1/reporting/finance/opening-balances/csv",
                HttpMethod.GET,
                new HttpEntity<>(sellerHeaders),
                String.class);

        assertThat(csvResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csvResponse.getHeaders().getContentType().toString()).contains("text/csv");
        String csv = csvResponse.getBody();
        assertThat(csv).isNotNull();
        assertThat(csv)
                .contains(
                        "Document No,Counterparty Code,Counterparty Name,Role,Amount,Reference No,Notes,Created Date");
        assertThat(csv).contains("D4S-OPB-0000001");
    }

    @Test
    void goodsSuppliedReportAndCsvExport() {
        HttpHeaders sellerHeaders = headers(SELLER_USER, SELLER);

        // 1. JSON Report without date filters
        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/finance/goods-supplied",
                HttpMethod.GET,
                new HttpEntity<>(sellerHeaders),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.has("lines")).isTrue();
        assertThat(body.get("lines").size()).isGreaterThanOrEqualTo(2);
        assertThat(body.get("lineCount").asInt()).isGreaterThanOrEqualTo(2);

        // Assert line reconciliation
        BigDecimal sumLines = BigDecimal.ZERO;
        for (JsonNode line : body.get("lines")) {
            assertThat(line.get("invoiceDocNumber").asText()).isNotBlank();
            assertThat(line.get("skuCode").asText()).isNotBlank();
            assertThat(line.get("skuName").asText()).isNotBlank();
            assertThat(new BigDecimal(line.get("quantity").asText())).isGreaterThan(BigDecimal.ZERO);
            assertThat(new BigDecimal(line.get("unitPrice").asText())).isGreaterThan(BigDecimal.ZERO);
            BigDecimal lineTotal = new BigDecimal(line.get("lineTotal").asText());
            sumLines = sumLines.add(lineTotal);
        }
        assertThat(new BigDecimal(body.get("totalGrossAmount").asText())).isEqualByComparingTo(sumLines);

        // 2. Filter by society
        ResponseEntity<JsonNode> societyFiltered = http.exchange(
                "/v1/reporting/finance/goods-supplied?societyId=" + BUYER,
                HttpMethod.GET,
                new HttpEntity<>(sellerHeaders),
                JsonNode.class);
        assertThat(societyFiltered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(societyFiltered.getBody().get("lines").size()).isGreaterThanOrEqualTo(2);

        // 3. Filter by date in future returns empty (query params 'from' and 'to' per openapi)
        ResponseEntity<JsonNode> emptyFiltered = http.exchange(
                "/v1/reporting/finance/goods-supplied?from=2099-01-01&to=2099-01-02",
                HttpMethod.GET,
                new HttpEntity<>(sellerHeaders),
                JsonNode.class);
        assertThat(emptyFiltered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(emptyFiltered.getBody().get("lines")).isEmpty();
        assertThat(emptyFiltered.getBody().get("lineCount").asInt()).isZero();

        // 4. CSV Export
        ResponseEntity<String> csvResponse = http.exchange(
                "/v1/reporting/finance/goods-supplied/csv",
                HttpMethod.GET,
                new HttpEntity<>(sellerHeaders),
                String.class);

        assertThat(csvResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csvResponse.getHeaders().getContentType().toString()).contains("text/csv");
        String csv = csvResponse.getBody();
        assertThat(csv).isNotNull();
        assertThat(csv)
                .contains(
                        "Invoice No,Date,Society,SKU Code,Product Name,UOM,Quantity,Unit Price,Tax Rate,Tax Amount,Line Total");
    }

    @Test
    void creditVoucherDetailsAndPrint() {
        HttpHeaders sellerHeaders = headers(SELLER_USER, SELLER);

        // 1. Voucher details
        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/finance/credit-vouchers/" + invoiceId,
                HttpMethod.GET,
                new HttpEntity<>(sellerHeaders),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode voucher = response.getBody();
        assertThat(voucher).isNotNull();
        assertThat(voucher.get("voucherNumber").asText()).startsWith("CPV-");
        assertThat(voucher.get("sellerName").asText()).isNotBlank();
        assertThat(voucher.get("buyerName").asText()).isNotBlank();
        assertThat(voucher.get("lines").size()).isGreaterThanOrEqualTo(2);
        assertThat(new BigDecimal(voucher.get("grossAmount").asText())).isGreaterThan(BigDecimal.ZERO);

        // 2. Voucher print link endpoint
        ResponseEntity<JsonNode> printResponse = http.exchange(
                "/v1/reporting/finance/credit-vouchers/" + invoiceId + "/print",
                HttpMethod.GET,
                new HttpEntity<>(sellerHeaders),
                JsonNode.class);

        // If renderer is available it returns 200 with url; if Chromium is missing in CI/local it returns 422
        assertThat(printResponse.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
