package lk.coopfed.knoweb.m8reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ShopReportsHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY_A = UUID.fromString("0190e888-0000-7000-8000-000000000001");
    private static final UUID ENTITY_B = UUID.fromString("0190e888-0000-7000-8000-000000000002");
    private static final UUID USER_A = UUID.fromString("0190e888-0000-7000-8000-000000000010");

    @Autowired
    private TestRestTemplate http;

    private UUID shopA1;
    private UUID shopA2;
    private UUID shopB1;

    @BeforeEach
    void setUp() {
        superuserJdbc().execute("TRUNCATE reporting.shop_target CASCADE");
        superuserJdbc().execute("TRUNCATE reporting.shop_sale_fact CASCADE");
        superuserJdbc().execute("TRUNCATE reporting.shop_sale_line_fact CASCADE");
        superuserJdbc()
                .execute(
                        "DELETE FROM party.location WHERE owner_entity_id IN ('" + ENTITY_A + "', '" + ENTITY_B + "')");

        shopA1 = Ids.next();
        shopA2 = Ids.next();
        shopB1 = Ids.next();

        // Seed locations in party.location
        superuserJdbc()
                .update(
                        """
                INSERT INTO party.location (location_id, owner_entity_id, location_code, location_type, name_en)
                VALUES (?, ?, ?, 'SHOP', ?)
                """,
                        shopA1,
                        ENTITY_A,
                        "SH-A1",
                        "Shop A1 Colombo");

        superuserJdbc()
                .update(
                        """
                INSERT INTO party.location (location_id, owner_entity_id, location_code, location_type, name_en)
                VALUES (?, ?, ?, 'SHOP', ?)
                """,
                        shopA2,
                        ENTITY_A,
                        "SH-A2",
                        "Shop A2 Kandy");

        superuserJdbc()
                .update(
                        """
                INSERT INTO party.location (location_id, owner_entity_id, location_code, location_type, name_en)
                VALUES (?, ?, ?, 'SHOP', ?)
                """,
                        shopB1,
                        ENTITY_B,
                        "SH-B1",
                        "Shop B1 Galle");
    }

    @Test
    void dailyShopReportReturnsAggregatedMetricsAndShopBreakdown() {
        // Target for shopA1: 2026-10-01 to 2026-10-10 (10 days), target 1000.00 -> daily target = 100.00
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_target
                       (target_id, owner_entity_id, location_id, period_start, period_end, target_amount, currency, created_at, updated_at)
                VALUES (?, ?, ?, date '2026-10-01', date '2026-10-10', 1000.00, 'LKR', now(), now())
                """,
                        Ids.next(),
                        ENTITY_A,
                        shopA1);

        // Sale 1: shopA1 on 2026-10-05
        UUID rcp1 = Ids.next();
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_sale_fact
                       (receipt_id, owner_entity_id, location_id, business_date, doc_number, net, tax, gross, lines, occurred_at, event_id)
                VALUES (?, ?, ?, date '2026-10-05', 'RCP-01', 100.00, 20.00, 120.00, 1, now(), ?)
                """,
                        rcp1,
                        ENTITY_A,
                        shopA1,
                        Ids.next());
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_sale_line_fact
                       (line_id, receipt_id, line_no, owner_entity_id, location_id, business_date, sku_id, qty, line_total)
                VALUES (?, ?, 1, ?, ?, date '2026-10-05', ?, 5.000, 120.00)
                """,
                        Ids.next(),
                        rcp1,
                        ENTITY_A,
                        shopA1,
                        Ids.next());

        // Sale 2: shopA2 on 2026-10-05 (2 receipts)
        UUID rcp2 = Ids.next();
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_sale_fact
                       (receipt_id, owner_entity_id, location_id, business_date, doc_number, net, tax, gross, lines, occurred_at, event_id)
                VALUES (?, ?, ?, date '2026-10-05', 'RCP-02', 40.00, 5.00, 45.00, 1, now(), ?)
                """,
                        rcp2,
                        ENTITY_A,
                        shopA2,
                        Ids.next());
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_sale_line_fact
                       (line_id, receipt_id, line_no, owner_entity_id, location_id, business_date, sku_id, qty, line_total)
                VALUES (?, ?, 1, ?, ?, date '2026-10-05', ?, 2.000, 45.00)
                """,
                        Ids.next(),
                        rcp2,
                        ENTITY_A,
                        shopA2,
                        Ids.next());

        UUID rcp3 = Ids.next();
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_sale_fact
                       (receipt_id, owner_entity_id, location_id, business_date, doc_number, net, tax, gross, lines, occurred_at, event_id)
                VALUES (?, ?, ?, date '2026-10-05', 'RCP-03', 30.00, 5.00, 35.00, 1, now(), ?)
                """,
                        rcp3,
                        ENTITY_A,
                        shopA2,
                        Ids.next());
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_sale_line_fact
                       (line_id, receipt_id, line_no, owner_entity_id, location_id, business_date, sku_id, qty, line_total)
                VALUES (?, ?, 1, ?, ?, date '2026-10-05', ?, 1.000, 35.00)
                """,
                        Ids.next(),
                        rcp3,
                        ENTITY_A,
                        shopA2,
                        Ids.next());

        // Call Daily Shop Report endpoint as ENTITY_A user
        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/shop-reports/daily?date=2026-10-05",
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();

        // Verify summary
        JsonNode summary = body.get("summary");
        assertThat(summary.get("businessDate").asText()).isEqualTo("2026-10-05");
        assertThat(summary.get("totalGrossSales").asText()).isEqualTo("200.00");
        assertThat(summary.get("totalNetSales").asText()).isEqualTo("170.00");
        assertThat(summary.get("totalTaxAmount").asText()).isEqualTo("30.00");
        assertThat(summary.get("totalTransactions").asLong()).isEqualTo(3L);
        assertThat(summary.get("averageTransactionValue").asText()).isEqualTo("66.67");
        assertThat(summary.get("totalItemsSold").asText()).isEqualTo("8.000");
        assertThat(summary.get("totalTargetAmount").asText()).isEqualTo("100.00");
        assertThat(summary.get("totalVarianceAmount").asText()).isEqualTo("100.00");
        assertThat(summary.get("overallAchievementPercent").asText()).isEqualTo("200.0");

        // Verify shop breakdown
        JsonNode shops = body.get("shops");
        assertThat(shops.isArray()).isTrue();
        assertThat(shops.size()).isGreaterThanOrEqualTo(2);

        JsonNode shop1 = null;
        JsonNode shop2 = null;
        for (JsonNode s : shops) {
            if (s.get("locationId").asText().equals(shopA1.toString())) {
                shop1 = s;
            } else if (s.get("locationId").asText().equals(shopA2.toString())) {
                shop2 = s;
            }
        }
        assertThat(shop1).isNotNull();
        assertThat(shop1.get("locationCode").asText()).isEqualTo("SH-A1");
        assertThat(shop1.get("grossSales").asText()).isEqualTo("120.00");
        assertThat(shop1.get("netSales").asText()).isEqualTo("100.00");
        assertThat(shop1.get("taxAmount").asText()).isEqualTo("20.00");
        assertThat(shop1.get("transactionCount").asLong()).isEqualTo(1L);
        assertThat(shop1.get("averageTransactionValue").asText()).isEqualTo("120.00");
        assertThat(shop1.get("itemsSold").asText()).isEqualTo("5.000");
        assertThat(shop1.get("targetAmount").asText()).isEqualTo("100.00");
        assertThat(shop1.get("varianceAmount").asText()).isEqualTo("20.00");
        assertThat(shop1.get("achievementPercent").asText()).isEqualTo("120.0");
        assertThat(shop1.get("performanceState").asText()).isEqualTo("TARGET_MET");

        assertThat(shop2).isNotNull();
        assertThat(shop2.get("locationCode").asText()).isEqualTo("SH-A2");
        assertThat(shop2.get("grossSales").asText()).isEqualTo("80.00");
        assertThat(shop2.get("netSales").asText()).isEqualTo("70.00");
        assertThat(shop2.get("taxAmount").asText()).isEqualTo("10.00");
        assertThat(shop2.get("transactionCount").asLong()).isEqualTo(2L);
        assertThat(shop2.get("averageTransactionValue").asText()).isEqualTo("40.00");
        assertThat(shop2.get("itemsSold").asText()).isEqualTo("3.000");
        assertThat(shop2.get("targetAmount").isNull()).isTrue();
        assertThat(shop2.get("performanceState").asText()).isEqualTo("NO_TARGET");
    }

    @Test
    void dailyShopReportFiltersByLocation() {
        // Insert sale for shopA1
        UUID rcp1 = Ids.next();
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_sale_fact
                       (receipt_id, owner_entity_id, location_id, business_date, doc_number, net, tax, gross, lines, occurred_at, event_id)
                VALUES (?, ?, ?, date '2026-10-05', 'RCP-01', 90.00, 10.00, 100.00, 1, now(), ?)
                """,
                        rcp1,
                        ENTITY_A,
                        shopA1,
                        Ids.next());

        // Insert sale for shopA2
        UUID rcp2 = Ids.next();
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_sale_fact
                       (receipt_id, owner_entity_id, location_id, business_date, doc_number, net, tax, gross, lines, occurred_at, event_id)
                VALUES (?, ?, ?, date '2026-10-05', 'RCP-02', 50.00, 0.00, 50.00, 1, now(), ?)
                """,
                        rcp2,
                        ENTITY_A,
                        shopA2,
                        Ids.next());

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/shop-reports/daily?date=2026-10-05&locationId=" + shopA1,
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("summary").get("totalGrossSales").asText()).isEqualTo("100.00");
        assertThat(body.get("shops").size()).isEqualTo(1);
        assertThat(body.get("shops").get(0).get("locationId").asText()).isEqualTo(shopA1.toString());
    }

    @Test
    void dailyShopReportEnforcesLocationScopeForbidden() {
        // User scoped to shopA1 requests report for shopA2 -> 403 Forbidden
        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/shop-reports/daily?date=2026-10-05&locationId=" + shopA2,
                HttpMethod.GET,
                new HttpEntity<>(asLocation(USER_A, ENTITY_A, shopA1)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void dailyShopReportUnknownLocationReturnsNotFound() {
        UUID unknownLoc = UUID.randomUUID();
        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/shop-reports/daily?date=2026-10-05&locationId=" + unknownLoc,
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void monthlyShopReportReturnsTrendAndProratedTargetComparison() {
        // Target for shopA1: 2026-10-01 to 2026-10-31 (31 days), target 3100.00
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_target
                       (target_id, owner_entity_id, location_id, period_start, period_end, target_amount, currency, created_at, updated_at)
                VALUES (?, ?, ?, date '2026-10-01', date '2026-10-31', 3100.00, 'LKR', now(), now())
                """,
                        Ids.next(),
                        ENTITY_A,
                        shopA1);

        // Sales for shopA1 on Oct 2 and Oct 15
        UUID rcp1 = Ids.next();
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_sale_fact
                       (receipt_id, owner_entity_id, location_id, business_date, doc_number, net, tax, gross, lines, occurred_at, event_id)
                VALUES (?, ?, ?, date '2026-10-02', 'RCP-01', 450.00, 50.00, 500.00, 1, now(), ?)
                """,
                        rcp1,
                        ENTITY_A,
                        shopA1,
                        Ids.next());
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_sale_line_fact
                       (line_id, receipt_id, line_no, owner_entity_id, location_id, business_date, sku_id, qty, line_total)
                VALUES (?, ?, 1, ?, ?, date '2026-10-02', ?, 10.000, 500.00)
                """,
                        Ids.next(),
                        rcp1,
                        ENTITY_A,
                        shopA1,
                        Ids.next());

        UUID rcp2 = Ids.next();
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_sale_fact
                       (receipt_id, owner_entity_id, location_id, business_date, doc_number, net, tax, gross, lines, occurred_at, event_id)
                VALUES (?, ?, ?, date '2026-10-15', 'RCP-02', 550.00, 50.00, 600.00, 1, now(), ?)
                """,
                        rcp2,
                        ENTITY_A,
                        shopA1,
                        Ids.next());
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_sale_line_fact
                       (line_id, receipt_id, line_no, owner_entity_id, location_id, business_date, sku_id, qty, line_total)
                VALUES (?, ?, 1, ?, ?, date '2026-10-15', ?, 15.000, 600.00)
                """,
                        Ids.next(),
                        rcp2,
                        ENTITY_A,
                        shopA1,
                        Ids.next());

        // Sale for shopA2 on Oct 15
        UUID rcp3 = Ids.next();
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_sale_fact
                       (receipt_id, owner_entity_id, location_id, business_date, doc_number, net, tax, gross, lines, occurred_at, event_id)
                VALUES (?, ?, ?, date '2026-10-15', 'RCP-03', 380.00, 20.00, 400.00, 1, now(), ?)
                """,
                        rcp3,
                        ENTITY_A,
                        shopA2,
                        Ids.next());
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_sale_line_fact
                       (line_id, receipt_id, line_no, owner_entity_id, location_id, business_date, sku_id, qty, line_total)
                VALUES (?, ?, 1, ?, ?, date '2026-10-15', ?, 8.000, 400.00)
                """,
                        Ids.next(),
                        rcp3,
                        ENTITY_A,
                        shopA2,
                        Ids.next());

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/shop-reports/monthly?yearMonth=2026-10",
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();

        // Summary
        JsonNode summary = body.get("summary");
        assertThat(summary.get("yearMonth").asText()).isEqualTo("2026-10");
        assertThat(summary.get("periodStart").asText()).isEqualTo("2026-10-01");
        assertThat(summary.get("periodEnd").asText()).isEqualTo("2026-10-31");
        assertThat(summary.get("totalGrossSales").asText()).isEqualTo("1500.00");
        assertThat(summary.get("totalNetSales").asText()).isEqualTo("1380.00");
        assertThat(summary.get("totalTaxAmount").asText()).isEqualTo("120.00");
        assertThat(summary.get("totalTransactions").asLong()).isEqualTo(3L);
        assertThat(summary.get("averageTransactionValue").asText()).isEqualTo("500.00");
        assertThat(summary.get("totalItemsSold").asText()).isEqualTo("33.000");
        assertThat(summary.get("totalTargetAmount").asText()).isEqualTo("3100.00");
        assertThat(summary.get("totalVarianceAmount").asText()).isEqualTo("-1600.00");
        assertThat(summary.get("overallAchievementPercent").asText()).isEqualTo("48.4");

        // Daily trend has 31 days
        JsonNode dailyTrend = body.get("dailyTrend");
        assertThat(dailyTrend.isArray()).isTrue();
        assertThat(dailyTrend.size()).isEqualTo(31);

        JsonNode day2 = dailyTrend.get(1); // Oct 2
        assertThat(day2.get("businessDate").asText()).isEqualTo("2026-10-02");
        assertThat(day2.get("grossSales").asText()).isEqualTo("500.00");
        assertThat(day2.get("itemsSold").asText()).isEqualTo("10.000");

        JsonNode day15 = dailyTrend.get(14); // Oct 15
        assertThat(day15.get("businessDate").asText()).isEqualTo("2026-10-15");
        assertThat(day15.get("grossSales").asText()).isEqualTo("1000.00");
        assertThat(day15.get("itemsSold").asText()).isEqualTo("23.000");

        // Shops comparison
        JsonNode shops = body.get("shops");
        assertThat(shops.isArray()).isTrue();
        assertThat(shops.size()).isGreaterThanOrEqualTo(2);

        JsonNode shop1 = null;
        for (JsonNode s : shops) {
            if (s.get("locationId").asText().equals(shopA1.toString())) {
                shop1 = s;
                break;
            }
        }
        assertThat(shop1).isNotNull();
        assertThat(shop1.get("grossSales").asText()).isEqualTo("1100.00");
        assertThat(shop1.get("targetAmount").asText()).isEqualTo("3100.00");
        assertThat(shop1.get("varianceAmount").asText()).isEqualTo("-2000.00");
        assertThat(shop1.get("achievementPercent").asText()).isEqualTo("35.5");
        assertThat(shop1.get("performanceState").asText()).isEqualTo("BEHIND_TARGET");
    }

    @Test
    void monthlyShopReportInvalidMonthReturns400() {
        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/shop-reports/monthly?yearMonth=not-a-month",
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void monthlyShopReportEnforcesLocationScopeForbidden() {
        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/shop-reports/monthly?yearMonth=2026-10&locationId=" + shopA2,
                HttpMethod.GET,
                new HttpEntity<>(asLocation(USER_A, ENTITY_A, shopA1)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private static HttpHeaders asEntity(UUID user, UUID entity) {
        return TestIdentityProvider.entityWideHeaders(user, entity);
    }

    private static HttpHeaders asLocation(UUID user, UUID entity, UUID location) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityProvider.token(
                user, entity, "OWN", claims -> claims.claim("scopes", List.of(entity + "/" + location))));
        headers.set("X-Scope-Entity", entity.toString());
        headers.set("X-Scope-Location", location.toString());
        return headers;
    }
}
