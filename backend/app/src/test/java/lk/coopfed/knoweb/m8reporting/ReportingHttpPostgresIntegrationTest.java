package lk.coopfed.knoweb.m8reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.m4trading.api.DeliveryNoteDispatched;
import lk.coopfed.knoweb.m4trading.api.GrnConfirmed;
import lk.coopfed.knoweb.m4trading.api.GrnLineConfirmed;
import lk.coopfed.knoweb.m4trading.api.InvoiceIssued;
import lk.coopfed.knoweb.m4trading.api.OrderAccepted;
import lk.coopfed.knoweb.m4trading.api.OrderLineSummary;
import lk.coopfed.knoweb.m4trading.api.OrderSubmitted;
import lk.coopfed.knoweb.m5inventory.api.StockMoved;
import lk.coopfed.knoweb.m8reporting.ProjectionHarness.Delivery;
import lk.coopfed.knoweb.m8reporting.api.ReportRunRequested;
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
 * The contract of the slice's operations (28A section 9, "Contract: every operation"), through
 * HTTP as a client calls them, over projections fed with the payloads of M4's and M5's event
 * records: a seller (the Federation), a buyer (a distributor) and a third entity that trades with
 * neither; the Federation view.
 */
class ReportingHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID SELLER = TEST_FEDERATION;
    private static final UUID BUYER = UUID.fromString("0190e888-0000-7000-8000-000000000002");
    private static final UUID OTHER = UUID.fromString("0190e888-0000-7000-8000-000000000003");
    private static final UUID USER = UUID.fromString("0190e888-0000-7000-8000-000000000010");
    private static final List<String> TABLES = List.of(
            "reporting.stock_position",
            "reporting.trade_document_event",
            "reporting.trade_line_fact",
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
    Clock clock;

    private ProjectionHarness harness;
    private UUID warehouse;
    private UUID shop;
    private UUID sku;
    private UUID note;
    private LocalDate today;

    @BeforeEach
    void arrange() {
        harness = new ProjectionHarness(mapper, superuserJdbc());
        harness.empty(TABLES);
        Instant now = clock.instant();
        today = LocalDate.ofInstant(now, ZoneId.of("Asia/Colombo"));
        warehouse = Ids.next();
        shop = Ids.next();
        sku = Ids.next();

        // The buyer's stock: 10 at the warehouse at 90.25, 4 at a shop at 90.25.
        harness.deliver(moved(BUYER, warehouse, "10", "90.2500", now), stock::on);
        harness.deliver(moved(BUYER, shop, "4", "90.2500", now), stock::on);

        // One order received and invoiced, one dispatched and in transit, one waiting.
        UUID relationship = Ids.next();
        List<Delivery> events = new ArrayList<>();
        UUID received = Ids.next();
        UUID receivedNote = Ids.next();
        UUID grn = Ids.next();
        OrderLineSummary line = new OrderLineSummary(
                Ids.next(), 1, sku, "EA", new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("90.2500"));
        events.add(harness.event(
                OrderSubmitted.TYPE,
                BUYER,
                now,
                new OrderSubmitted(received, "D-ORD-1", relationship, BUYER, SELLER, null, List.of(line))));
        events.add(harness.event(
                OrderAccepted.TYPE,
                SELLER,
                now,
                new OrderAccepted(received, relationship, BUYER, SELLER, Ids.next(), null, null, List.of(line))));
        events.add(harness.event(
                DeliveryNoteDispatched.TYPE,
                SELLER,
                now,
                new DeliveryNoteDispatched(receivedNote, "F-DN-1", SELLER, BUYER, now, null, null)));
        events.add(harness.event(
                GrnConfirmed.TYPE,
                BUYER,
                now,
                new GrnConfirmed(
                        grn,
                        "D-GRN-1",
                        BUYER,
                        warehouse,
                        SELLER,
                        relationship,
                        Ids.next(),
                        receivedNote,
                        null,
                        now,
                        false,
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
                                new BigDecimal("10"),
                                BigDecimal.ZERO,
                                new BigDecimal("90.2500"))))));
        events.add(harness.event(
                InvoiceIssued.TYPE,
                SELLER,
                now,
                new InvoiceIssued(
                        Ids.next(),
                        "F-INV-1",
                        relationship,
                        SELLER,
                        BUYER,
                        "V1",
                        null,
                        List.of(grn),
                        today,
                        today.plusDays(30),
                        new BigDecimal("902.50"),
                        new BigDecimal("162.45"),
                        new BigDecimal("1064.95"),
                        "hash")));
        UUID transit = Ids.next();
        note = Ids.next();
        events.add(harness.event(
                OrderSubmitted.TYPE,
                BUYER,
                now,
                new OrderSubmitted(transit, "D-ORD-2", relationship, BUYER, SELLER, null, List.of())));
        events.add(harness.event(
                OrderAccepted.TYPE,
                SELLER,
                now,
                new OrderAccepted(transit, relationship, BUYER, SELLER, Ids.next(), null, null, List.of())));
        events.add(harness.event(
                DeliveryNoteDispatched.TYPE,
                SELLER,
                now,
                new DeliveryNoteDispatched(note, "F-DN-2", SELLER, BUYER, now, null, null)));
        events.add(harness.event(
                OrderSubmitted.TYPE,
                BUYER,
                now,
                new OrderSubmitted(Ids.next(), "D-ORD-3", relationship, BUYER, SELLER, null, List.of())));
        for (Delivery event : events) {
            harness.deliver(event, trade::on);
        }
        kernel.reset();
    }

    @AfterEach
    void clean() {
        harness.empty(TABLES);
    }

    @Test
    void theDefinitionsAreTheThreeOfTheDemo() {
        JsonNode definitions = get("/v1/reporting/reports", as(SELLER)).getBody();
        assertThat(definitions)
                .extracting(d -> d.get("reportId").asText())
                .containsExactly("stock-position", "trade-by-distributor", "invoices-issued");
        assertThat(definitions.get(1).get("period").asBoolean()).isTrue();
        assertThat(definitions.get(0).get("location").asBoolean()).isTrue();
    }

    @Test
    void theStockPositionShowsTheOwnerItsStockAndValueAndNobodyElseAnything() {
        JsonNode report =
                get("/v1/reporting/reports/stock-position/data", as(BUYER)).getBody();
        assertThat(report.get("rows")).hasSize(2);
        assertThat(report.get("columns")).extracting(c -> c.get("key").asText()).contains("value");
        assertThat(report.get("totals").get("qty").decimalValue()).isEqualByComparingTo("14");
        assertThat(report.get("totals").get("value").decimalValue()).isEqualByComparingTo("1263.50");

        JsonNode oneShop = get("/v1/reporting/reports/stock-position/data?locationId=" + shop, as(BUYER))
                .getBody();
        assertThat(oneShop.get("rows")).hasSize(1);
        assertThat(oneShop.get("rows").get(0).get("qty").decimalValue()).isEqualByComparingTo("4");

        // The seller is the buyer's counterparty, not the owner of its stock: nothing.
        assertThat(get("/v1/reporting/reports/stock-position/data", as(SELLER))
                        .getBody()
                        .get("rows"))
                .isEmpty();

        // The Federation view reads every row, the value included.
        JsonNode view = get("/v1/reporting/reports/stock-position/data", federationView())
                .getBody();
        assertThat(view.get("rows")).hasSize(2);
        assertThat(view.get("totals").get("value").decimalValue()).isEqualByComparingTo("1263.50");
    }

    @Test
    void tradeByDistributorShowsTheReceivedVolumeToBothPartiesAndToNobodyElse() {
        String url = "/v1/reporting/reports/trade-by-distributor/data?from=" + today.minusDays(1) + "&to=" + today;
        for (UUID party : List.of(SELLER, BUYER)) {
            JsonNode report = get(url, as(party)).getBody();
            assertThat(report.get("rows")).as("party %s", party).hasSize(1);
            JsonNode row = report.get("rows").get(0);
            assertThat(row.get("date").asText()).isEqualTo(today.toString());
            assertThat(row.get("qty").decimalValue()).isEqualByComparingTo("10");
            assertThat(row.get("value").decimalValue()).isEqualByComparingTo("902.50");
            assertThat(report.get("freshness").isNull()).isFalse();
        }
        assertThat(get(url, as(OTHER)).getBody().get("rows")).isEmpty();

        // A period that ends before the trade: nothing.
        assertThat(get(
                                "/v1/reporting/reports/trade-by-distributor/data?from=" + today.minusDays(9) + "&to="
                                        + today.minusDays(8),
                                as(SELLER))
                        .getBody()
                        .get("rows"))
                .isEmpty();
    }

    @Test
    void theInvoicesIssuedAreListedWithTheirTotals() {
        JsonNode report = get("/v1/reporting/reports/invoices-issued/data?from=" + today + "&to=" + today, as(BUYER))
                .getBody();
        assertThat(report.get("rows")).hasSize(1);
        assertThat(report.get("rows").get(0).get("number").asText()).isEqualTo("F-INV-1");
        assertThat(report.get("totals").get("gross").decimalValue()).isEqualByComparingTo("1064.95");
        assertThat(report.get("totals").get("tax").decimalValue()).isEqualByComparingTo("162.45");
    }

    @Test
    void aReportRefusesAMissingOrReversedPeriodAndAnUnknownReport() {
        ResponseEntity<JsonNode> missing = get("/v1/reporting/reports/invoices-issued/data", as(SELLER));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(missing.getBody().get("code").asText()).isEqualTo("m8.report.period_required");

        ResponseEntity<JsonNode> reversed = get(
                "/v1/reporting/reports/invoices-issued/data?from=" + today + "&to=" + today.minusDays(1), as(SELLER));
        assertThat(reversed.getBody().get("code").asText()).isEqualTo("m8.report.period_invalid");

        ResponseEntity<JsonNode> unknown = get("/v1/reporting/reports/no-such-report/data", as(SELLER));
        assertThat(unknown.getBody().get("code").asText()).isEqualTo("m8.report.unknown");
    }

    @Test
    void theCsvIsTheSameRowsUnderAHeaderInTheCallersLanguage() {
        ResponseEntity<String> csv = http.exchange(
                "/v1/reporting/reports/trade-by-distributor/csv?from=" + today + "&to=" + today,
                HttpMethod.GET,
                new HttpEntity<>(as(SELLER)),
                String.class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getHeaders().getContentType().isCompatibleWith(MediaType.parseMediaType("text/csv")))
                .isTrue();
        assertThat(csv.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("trade-by-distributor_" + today + "_" + today + ".csv");
        String[] lines = csv.getBody().split("\r\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).isEqualTo("Date,Seller,Buyer,Item code,Item,Quantity,Value");
        assertThat(lines[1]).startsWith(today + ",").endsWith(",10.000,902.50");
    }

    @Test
    void theDashboardCountsTheCallersDocumentsAndShowsTheOwnersStockValue() {
        JsonNode seller = get("/v1/reporting/dashboard", as(SELLER)).getBody();
        assertThat(tile(seller, "open-orders")).isEqualTo("1");
        assertThat(tile(seller, "deliveries-in-transit")).isEqualTo("1");
        assertThat(tile(seller, "grns-today")).isEqualTo("1");
        assertThat(new BigDecimal(tile(seller, "stock-value"))).isEqualByComparingTo("0");

        JsonNode buyer = get("/v1/reporting/dashboard", as(BUYER)).getBody();
        assertThat(tile(buyer, "open-orders")).isEqualTo("1");
        assertThat(tile(buyer, "deliveries-in-transit")).isEqualTo("1");
        assertThat(new BigDecimal(tile(buyer, "stock-value"))).isEqualByComparingTo("1263.50");

        JsonNode other = get("/v1/reporting/dashboard", as(OTHER)).getBody();
        assertThat(tile(other, "open-orders")).isEqualTo("0");
        assertThat(tile(other, "grns-today")).isEqualTo("0");
    }

    @Test
    void aPrintRunIsRecordedRequestedAuditedAndPublished() {
        HttpHeaders headers = as(SELLER);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/reports/invoices-issued/runs",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("from", today.toString(), "to", today.toString(), "language", "si"), headers),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().get("status").asText()).isEqualTo("REQUESTED");
        assertThat(response.getBody().get("language").asText()).isEqualTo("si");
        UUID runId = UUID.fromString(response.getBody().get("runId").asText());
        assertThat(kernel.committedAudit()).extracting(a -> a.eventType()).containsExactly("REPORT_RUN_REQUESTED");
        assertThat(kernel.committedEvents())
                .containsExactly(new ReportRunRequested(runId, "invoices-issued", today, today, null, "si"));

        JsonNode run = get("/v1/reporting/runs/" + runId, as(SELLER)).getBody();
        assertThat(run.get("status").asText()).isEqualTo("REQUESTED");
        assertThat(get("/v1/reporting/runs/" + runId, as(BUYER)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aPrintRunWithoutItsPeriodIsRefusedAndNothingIsCommitted() {
        HttpHeaders headers = as(SELLER);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> refused = http.exchange(
                "/v1/reporting/reports/trade-by-distributor/runs",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("language", "en"), headers),
                JsonNode.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("m8.report.period_required");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static String tile(JsonNode dashboard, String tileId) {
        for (JsonNode tile : dashboard.get("tiles")) {
            if (tile.get("tileId").asText().equals(tileId)) {
                return tile.get("value").asText();
            }
        }
        throw new AssertionError("no tile " + tileId + " in " + dashboard);
    }

    private ResponseEntity<JsonNode> get(String url, HttpHeaders headers) {
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private static HttpHeaders as(UUID entity) {
        return TestIdentityProvider.entityWideHeaders(USER, entity);
    }

    private static HttpHeaders federationView() {
        HttpHeaders view = new HttpHeaders();
        view.setBearerAuth(TestIdentityProvider.entityWideToken(USER, TEST_FEDERATION, "FEDERATION_VIEW"));
        view.set("X-Scope-Entity", TEST_FEDERATION.toString());
        return view;
    }

    private Delivery moved(UUID owner, UUID location, String qty, String cost, Instant at) {
        return harness.event(
                StockMoved.TYPE,
                owner,
                at,
                new StockMoved(
                        Ids.next(),
                        owner,
                        location,
                        Ids.next(),
                        Ids.next(),
                        sku,
                        "GOOD",
                        "RECEIPT",
                        new BigDecimal(qty),
                        new BigDecimal(cost),
                        new BigDecimal(qty),
                        Ids.next(),
                        "central",
                        1));
    }
}
