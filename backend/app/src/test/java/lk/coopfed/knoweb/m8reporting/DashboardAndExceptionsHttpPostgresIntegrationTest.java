package lk.coopfed.knoweb.m8reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.m4trading.api.ChequeBounced;
import lk.coopfed.knoweb.m4trading.api.CreditNoteIssued;
import lk.coopfed.knoweb.m4trading.api.DeliveryNoteDispatched;
import lk.coopfed.knoweb.m4trading.api.DeliveryNoteIssued;
import lk.coopfed.knoweb.m4trading.api.DiscrepancyRaised;
import lk.coopfed.knoweb.m4trading.api.ExposureWarning;
import lk.coopfed.knoweb.m4trading.api.GrnConfirmed;
import lk.coopfed.knoweb.m4trading.api.GrnLineConfirmed;
import lk.coopfed.knoweb.m4trading.api.InvoiceDisputed;
import lk.coopfed.knoweb.m4trading.api.InvoiceIssued;
import lk.coopfed.knoweb.m4trading.api.OrderAccepted;
import lk.coopfed.knoweb.m4trading.api.OrderLineSummary;
import lk.coopfed.knoweb.m4trading.api.OrderSubmitted;
import lk.coopfed.knoweb.m4trading.api.PaymentReceiptRecorded;
import lk.coopfed.knoweb.m4trading.api.PaymentReceiptReversed;
import lk.coopfed.knoweb.m4trading.api.RecordPaymentReceipt.Settlement;
import lk.coopfed.knoweb.m5inventory.api.StockMoved;
import lk.coopfed.knoweb.m8reporting.ProjectionHarness.Delivery;
import lk.coopfed.knoweb.m8reporting.internal.projection.ShopSaleProjection;
import lk.coopfed.knoweb.m8reporting.internal.projection.StockPositionProjection;
import lk.coopfed.knoweb.m8reporting.internal.projection.TradeProjection;
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

/**
 * M8-04 rest, M8-05 and the demo's reports over HTTP: the settled, credited, fill-rate and
 * on-time figures from M4's payment, credit note, discrepancy, dispute and exposure events and
 * the till's receipts, as the dashboard, the exception queue and the reports show them to the
 * seller (the Federation), the buyer (a distributor) and a third entity.
 *
 * <p>The story, dated from today so that it never falls out of the eight weeks: an order of 10
 * at 100.00 committed for today, delivered short (8 received, on time), invoiced 944.00 three
 * days ago and due yesterday; 300.00 paid in cash, 44.00 credited, a 200.00 cheque that bounced;
 * so 600.00 is still due. A second order of 5 at 100.00 accepted and not delivered. Exposure
 * 600 + 500 = 1,100 against a limit of 1,000: 110 %. The buyer raised a discrepancy whose
 * window has ended and disputes the invoice; one of its lots is below zero, and its shop sold
 * 250.00 at the till.
 */
class DashboardAndExceptionsHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID SELLER = TEST_FEDERATION;
    private static final UUID BUYER = UUID.fromString("0190e888-0000-7000-8000-000000000102");
    private static final UUID OTHER = UUID.fromString("0190e888-0000-7000-8000-000000000103");
    private static final UUID USER = UUID.fromString("0190e888-0000-7000-8000-000000000110");
    private static final List<String> TABLES = List.of(
            "reporting.stock_position",
            "reporting.trade_document_event",
            "reporting.trade_line_fact",
            "reporting.trade_document_link",
            "reporting.trade_settlement_fact",
            "reporting.exposure_warning_event",
            "reporting.shop_sale_fact",
            "reporting.shop_sale_line_fact",
            "reporting.report_run");

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    StockPositionProjection stock;

    @Autowired
    TradeProjection trade;

    @Autowired
    ShopSaleProjection shopSales;

    @Autowired
    Clock clock;

    private ProjectionHarness harness;
    private LocalDate today;
    private UUID shop;
    private UUID receiptBounced;
    private UUID relationship;

    @BeforeEach
    void arrange() {
        harness = new ProjectionHarness(mapper, superuserJdbc());
        harness.empty(TABLES);
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        today = LocalDate.ofInstant(now, ZoneId.of("Asia/Colombo"));
        shop = Ids.next();
        UUID warehouse = Ids.next();
        UUID sku = Ids.next();
        relationship = Ids.next();

        UUID orderA = Ids.next();
        UUID orderB = Ids.next();
        UUID dn = Ids.next();
        UUID grn = Ids.next();
        UUID invoice = Ids.next();
        OrderLineSummary lineA = new OrderLineSummary(
                Ids.next(), 1, sku, "EA", new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("100.0000"));
        OrderLineSummary lineB = new OrderLineSummary(
                Ids.next(), 1, sku, "EA", new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("100.0000"));
        Instant earlier = now.minus(4, ChronoUnit.DAYS);

        List<Delivery> events = new ArrayList<>();
        events.add(harness.event(
                OrderSubmitted.TYPE,
                BUYER,
                earlier,
                new OrderSubmitted(orderA, "D-ORD-A", relationship, BUYER, SELLER, today, List.of(lineA))));
        events.add(harness.event(
                OrderAccepted.TYPE,
                SELLER,
                earlier,
                new OrderAccepted(orderA, relationship, BUYER, SELLER, Ids.next(), today, null, List.of(lineA))));
        events.add(harness.event(
                DeliveryNoteIssued.TYPE,
                SELLER,
                earlier,
                new DeliveryNoteIssued(dn, "F-DN-A", SELLER, BUYER, List.of(orderA), List.of(), warehouse)));
        events.add(harness.event(
                DeliveryNoteDispatched.TYPE,
                SELLER,
                earlier,
                new DeliveryNoteDispatched(dn, "F-DN-A", SELLER, BUYER, earlier, null, null)));
        events.add(harness.event(
                GrnConfirmed.TYPE,
                BUYER,
                earlier,
                new GrnConfirmed(
                        grn,
                        "D-GRN-A",
                        BUYER,
                        warehouse,
                        SELLER,
                        relationship,
                        Ids.next(),
                        dn,
                        null,
                        earlier,
                        true,
                        List.of(new GrnLineConfirmed(
                                Ids.next(),
                                1,
                                sku,
                                Ids.next(),
                                "B1",
                                LocalDate.of(2027, 1, 31),
                                new BigDecimal("120.00"),
                                "EA",
                                new BigDecimal("10"),
                                new BigDecimal("8"),
                                BigDecimal.ZERO,
                                new BigDecimal("100.0000"))))));
        events.add(harness.event(
                InvoiceIssued.TYPE,
                SELLER,
                now.minus(3, ChronoUnit.DAYS),
                new InvoiceIssued(
                        invoice,
                        "F-INV-A",
                        relationship,
                        SELLER,
                        BUYER,
                        "V1",
                        null,
                        List.of(grn),
                        today.minusDays(3),
                        today.minusDays(1),
                        new BigDecimal("800.00"),
                        new BigDecimal("144.00"),
                        new BigDecimal("944.00"),
                        "hash")));
        events.add(harness.event(
                PaymentReceiptRecorded.TYPE,
                SELLER,
                now,
                new PaymentReceiptRecorded(
                        Ids.next(),
                        "F-PRC-1",
                        relationship,
                        SELLER,
                        BUYER,
                        "CASH",
                        new BigDecimal("300.00"),
                        today,
                        List.of(new Settlement(invoice, new BigDecimal("300.00"))),
                        BigDecimal.ZERO)));
        events.add(harness.event(
                CreditNoteIssued.TYPE,
                SELLER,
                now,
                new CreditNoteIssued(
                        Ids.next(),
                        "F-CN-1",
                        invoice,
                        null,
                        SELLER,
                        BUYER,
                        new BigDecimal("37.29"),
                        new BigDecimal("6.71"),
                        new BigDecimal("44.00"),
                        "hash")));
        receiptBounced = Ids.next();
        UUID reversal = Ids.next();
        events.add(harness.event(
                PaymentReceiptRecorded.TYPE,
                SELLER,
                now,
                new PaymentReceiptRecorded(
                        receiptBounced,
                        "F-PRC-2",
                        relationship,
                        SELLER,
                        BUYER,
                        "CHEQUE",
                        new BigDecimal("200.00"),
                        today,
                        List.of(new Settlement(invoice, new BigDecimal("200.00"))),
                        BigDecimal.ZERO)));
        events.add(harness.event(
                PaymentReceiptReversed.TYPE,
                SELLER,
                now,
                new PaymentReceiptReversed(
                        reversal,
                        "F-PRC-3",
                        receiptBounced,
                        SELLER,
                        BUYER,
                        new BigDecimal("200.00"),
                        List.of(new Settlement(invoice, new BigDecimal("200.00"))),
                        "CHEQUE_BOUNCED")));
        events.add(harness.event(
                ChequeBounced.TYPE,
                SELLER,
                now,
                new ChequeBounced(receiptBounced, reversal, SELLER, BUYER, new BigDecimal("200.00"), "FUNDS")));
        events.add(harness.event(
                OrderSubmitted.TYPE,
                BUYER,
                now,
                new OrderSubmitted(orderB, "D-ORD-B", relationship, BUYER, SELLER, null, List.of(lineB))));
        events.add(harness.event(
                OrderAccepted.TYPE,
                SELLER,
                now,
                new OrderAccepted(orderB, relationship, BUYER, SELLER, Ids.next(), null, null, List.of(lineB))));
        events.add(harness.event(
                ExposureWarning.TYPE,
                SELLER,
                now,
                new ExposureWarning(
                        relationship,
                        SELLER,
                        BUYER,
                        new BigDecimal("1100.00"),
                        new BigDecimal("1000.00"),
                        80,
                        orderB)));
        events.add(harness.event(
                DiscrepancyRaised.TYPE,
                BUYER,
                earlier,
                new DiscrepancyRaised(
                        Ids.next(),
                        "D-DSC-1",
                        grn,
                        dn,
                        BUYER,
                        warehouse,
                        SELLER,
                        "SHORT",
                        now.minus(1, ChronoUnit.DAYS),
                        List.of())));
        events.add(
                harness.event(InvoiceDisputed.TYPE, BUYER, now, new InvoiceDisputed(invoice, SELLER, BUYER, "PRICE")));
        for (Delivery event : events) {
            harness.deliver(event, trade::on);
        }

        harness.deliver(
                harness.event(
                        StockMoved.TYPE,
                        BUYER,
                        now,
                        new StockMoved(
                                Ids.next(),
                                BUYER,
                                shop,
                                Ids.next(),
                                Ids.next(),
                                sku,
                                "GOOD",
                                "SALE",
                                new BigDecimal("-3"),
                                new BigDecimal("90.0000"),
                                new BigDecimal("-2"),
                                Ids.next(),
                                "central",
                                1)),
                stock::on);
        harness.deliver(harness.event("receipt.issued.v1", BUYER, now, receipt(sku, now)), shopSales::on);
        kernel.reset();
    }

    @AfterEach
    void clean() {
        harness.empty(TABLES);
    }

    @Test
    void theSellersDashboardShowsItsSalesReceivablesExposureAndServiceLevels() {
        JsonNode dashboard = get("/v1/reporting/dashboard", as(SELLER)).getBody();

        JsonNode sales = tile(dashboard, "sales");
        assertThat(new BigDecimal(sales.get("value").asText())).isEqualByComparingTo("800.00");
        assertThat(sales.get("trend")).hasSize(8);
        assertThat(sales.get("trend").get(7).get("value").asText()).isEqualTo("800.00");
        assertThat(sales.get("drillReportId").asText()).isEqualTo("invoices-issued");
        assertThat(value(dashboard, "receivables")).isEqualByComparingTo("600.00");
        assertThat(value(dashboard, "overdue-receivables")).isEqualByComparingTo("600.00");
        assertThat(value(dashboard, "exposure")).isEqualByComparingTo("110.0");
        assertThat(value(dashboard, "fill-rate")).isEqualByComparingTo("80.0");
        assertThat(value(dashboard, "on-time")).isEqualByComparingTo("100.0");
        // The discrepancy, the dispute, the bounced cheque and the exposure: the buyer's lot is not
        // the seller's.
        assertThat(value(dashboard, "exceptions")).isEqualByComparingTo("4");
        assertThat(ids(dashboard))
                .doesNotContain("purchases", "payables", "supplier-fill-rate", "shop-sales")
                .contains("open-orders", "stock-value");
    }

    @Test
    void theBuyersDashboardShowsItsPurchasesPayablesSuppliersAndShopSales() {
        JsonNode dashboard = get("/v1/reporting/dashboard", as(BUYER)).getBody();

        assertThat(value(dashboard, "purchases")).isEqualByComparingTo("800.00");
        assertThat(value(dashboard, "payables")).isEqualByComparingTo("600.00");
        assertThat(value(dashboard, "overdue-payables")).isEqualByComparingTo("600.00");
        assertThat(value(dashboard, "supplier-fill-rate")).isEqualByComparingTo("80.0");
        assertThat(value(dashboard, "supplier-on-time")).isEqualByComparingTo("100.0");
        assertThat(value(dashboard, "shop-sales")).isEqualByComparingTo("250.00");
        assertThat(value(dashboard, "exposure")).isEqualByComparingTo("110.0");
        assertThat(value(dashboard, "exceptions")).isEqualByComparingTo("5");
        assertThat(ids(dashboard)).doesNotContain("sales", "receivables", "fill-rate");

        JsonNode other = get("/v1/reporting/dashboard", as(OTHER)).getBody();
        assertThat(ids(other)).doesNotContain("sales", "purchases", "receivables", "payables", "exposure");
        assertThat(value(other, "exceptions")).isEqualByComparingTo("0");
    }

    @Test
    void theExceptionQueueListsWhatNeedsAPersonEscalatedFirst() {
        JsonNode seller = get("/v1/reporting/exceptions", as(SELLER)).getBody();
        assertThat(seller)
                .extracting(i -> i.get("kind").asText())
                .containsExactlyInAnyOrder(
                        "DISCREPANCY_OPEN", "INVOICE_DISPUTED", "CHEQUE_BOUNCED", "EXPOSURE_WARNING");
        // The discrepancy's window ended yesterday: escalated, so first.
        assertThat(seller.get(0).get("kind").asText()).isEqualTo("DISCREPANCY_OPEN");
        assertThat(seller.get(0).get("escalated").asBoolean()).isTrue();

        JsonNode bounced = item(seller, "CHEQUE_BOUNCED");
        assertThat(bounced.get("severity").asText()).isEqualTo("ALERT");
        assertThat(bounced.get("subjectId").asText()).isEqualTo(receiptBounced.toString());
        assertThat(bounced.get("documentNumber").asText()).isEqualTo("F-PRC-2");
        assertThat(bounced.get("amount").asText()).isEqualTo("200.00");
        assertThat(bounced.get("role").asText()).isEqualTo("SELLER");
        assertThat(bounced.get("counterpartyEntityId").asText()).isEqualTo(BUYER.toString());

        JsonNode exposure = item(seller, "EXPOSURE_WARNING");
        assertThat(exposure.get("subjectId").asText()).isEqualTo(relationship.toString());
        assertThat(new BigDecimal(exposure.get("amount").asText())).isEqualByComparingTo("1100.00");
        assertThat(new BigDecimal(exposure.get("percent").asText())).isEqualByComparingTo("110.0");

        JsonNode buyer = get("/v1/reporting/exceptions", as(BUYER)).getBody();
        assertThat(buyer).extracting(i -> i.get("kind").asText()).contains("NEGATIVE_STOCK", "CHEQUE_BOUNCED");
        assertThat(item(buyer, "CHEQUE_BOUNCED").get("role").asText()).isEqualTo("BUYER");
        assertThat(item(buyer, "CHEQUE_BOUNCED").get("counterpartyEntityId").asText())
                .isEqualTo(SELLER.toString());
        assertThat(new BigDecimal(item(buyer, "NEGATIVE_STOCK").get("amount").asText()))
                .isEqualByComparingTo("-2");

        assertThat(get("/v1/reporting/exceptions", as(OTHER)).getBody()).isEmpty();
    }

    @Test
    void theStatementAndTheAgeingShowWhatIsPaidCreditedAndStillDue() {
        JsonNode statement = get(
                        "/v1/reporting/reports/statement-of-account/data?from=" + today.minusDays(7) + "&to=" + today,
                        as(BUYER))
                .getBody();
        assertThat(statement.get("rows")).hasSize(1);
        JsonNode row = statement.get("rows").get(0);
        assertThat(row.get("number").asText()).isEqualTo("F-INV-A");
        assertThat(row.get("gross").asText()).isEqualTo("944.00");
        assertThat(row.get("paid").asText()).isEqualTo("300.00");
        assertThat(row.get("credited").asText()).isEqualTo("44.00");
        assertThat(row.get("outstanding").asText()).isEqualTo("600.00");
        // Due yesterday: one day overdue (two, should the test run across midnight).
        assertThat(row.get("daysOverdue").asText()).isIn("1", "2");

        JsonNode ageing =
                get("/v1/reporting/reports/receivables-ageing/data", as(SELLER)).getBody();
        assertThat(ageing.get("rows")).hasSize(1);
        assertThat(ageing.get("rows").get(0).get("d1to30").asText()).isEqualTo("600.00");
        assertThat(new BigDecimal(ageing.get("rows").get(0).get("notDue").asText()))
                .isEqualByComparingTo("0");
        assertThat(ageing.get("totals").get("outstanding").asText()).isEqualTo("600.00");
        assertThat(get("/v1/reporting/reports/receivables-ageing/data", as(OTHER))
                        .getBody()
                        .get("rows"))
                .isEmpty();
    }

    @Test
    void theFillRateReportAndTheShopSalesReports() {
        String period = "?from=" + today.minusDays(7) + "&to=" + today;
        JsonNode fill = get("/v1/reporting/reports/fill-rate-delivery/data" + period, as(SELLER))
                .getBody();
        assertThat(fill.get("rows")).hasSize(1);
        JsonNode row = fill.get("rows").get(0);
        assertThat(row.get("grns").asText()).isEqualTo("1");
        assertThat(new BigDecimal(row.get("expected").asText())).isEqualByComparingTo("10");
        assertThat(new BigDecimal(row.get("received").asText())).isEqualByComparingTo("8");
        assertThat(row.get("fillRate").asText()).isEqualTo("80.0");
        assertThat(row.get("onTime").asText()).isEqualTo("1");
        assertThat(row.get("onTimeRate").asText()).isEqualTo("100.0");
        // A rate is never added up.
        assertThat(fill.get("totals").has("fillRate")).isFalse();

        JsonNode byShop = get("/v1/reporting/reports/shop-sales-by-shop/data" + period, as(BUYER))
                .getBody();
        assertThat(byShop.get("rows")).hasSize(1);
        assertThat(byShop.get("rows").get(0).get("receipts").asText()).isEqualTo("1");
        assertThat(byShop.get("totals").get("gross").asText()).isEqualTo("250.00");
        JsonNode byItem = get("/v1/reporting/reports/shop-sales-by-item/data" + period, as(BUYER))
                .getBody();
        assertThat(new BigDecimal(byItem.get("rows").get(0).get("qty").asText()))
                .isEqualByComparingTo("3");
        // A shop's sales are its owner's: the seller reads none.
        assertThat(get("/v1/reporting/reports/shop-sales-by-shop/data" + period, as(SELLER))
                        .getBody()
                        .get("rows"))
                .isEmpty();
    }

    @Test
    void theRunHistoryListsTheEntitysPrintsOfAReport() {
        HttpHeaders headers = as(SELLER);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> requested = http.exchange(
                "/v1/reporting/reports/receivables-ageing/runs",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("language", "en"), headers),
                JsonNode.class);
        assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        JsonNode runs =
                get("/v1/reporting/reports/receivables-ageing/runs", as(SELLER)).getBody();
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).get("runId").asText())
                .isEqualTo(requested.getBody().get("runId").asText());
        assertThat(runs.get(0).get("status").asText()).isEqualTo("REQUESTED");
        assertThat(get("/v1/reporting/reports/receivables-ageing/runs", as(BUYER))
                        .getBody())
                .isEmpty();
        assertThat(get("/v1/reporting/reports/no-such-report/runs", as(SELLER))
                        .getBody()
                        .get("code")
                        .asText())
                .isEqualTo("m8.report.unknown");
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** The till's bundle (doc 32 section 3.1) with doc 18's column names: one line of 3 for 250.00. */
    private Map<String, Object> receipt(UUID sku, Instant at) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("document_id", Ids.next().toString());
        document.put("location_id", shop.toString());
        document.put("business_date", today.toString());
        document.put("issued_at", at.toString());
        document.put("doc_number_display", "S01-000001");
        document.put("net_amount", "211.86");
        document.put("tax_amount", "38.14");
        document.put("gross_amount", "250.00");
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("line_id", Ids.next().toString());
        line.put("line_no", 1);
        line.put("sku_id", sku.toString());
        line.put("qty", "3");
        line.put("line_total", "250.00");
        return Map.of("document", document, "lines", List.of(line));
    }

    private static JsonNode tile(JsonNode dashboard, String tileId) {
        for (JsonNode tile : dashboard.get("tiles")) {
            if (tile.get("tileId").asText().equals(tileId)) {
                return tile;
            }
        }
        throw new AssertionError("no tile " + tileId + " in " + dashboard);
    }

    private static BigDecimal value(JsonNode dashboard, String tileId) {
        return new BigDecimal(tile(dashboard, tileId).get("value").asText());
    }

    private static List<String> ids(JsonNode dashboard) {
        List<String> ids = new ArrayList<>();
        dashboard.get("tiles").forEach(t -> ids.add(t.get("tileId").asText()));
        return ids;
    }

    private static JsonNode item(JsonNode queue, String kind) {
        for (JsonNode item : queue) {
            if (item.get("kind").asText().equals(kind)) {
                return item;
            }
        }
        throw new AssertionError("no " + kind + " in " + queue);
    }

    private ResponseEntity<JsonNode> get(String url, HttpHeaders headers) {
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private static HttpHeaders as(UUID entity) {
        return TestIdentityProvider.entityWideHeaders(USER, entity);
    }
}
