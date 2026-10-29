package lk.coopfed.knoweb.m8reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.m8reporting.api.ProfitCenterCreated;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class ProfitCenterPnlHttpPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY_A = UUID.fromString("0190e888-0000-7000-8000-000000000001");
    private static final UUID ENTITY_B = UUID.fromString("0190e888-0000-7000-8000-000000000002");
    private static final UUID USER_A = UUID.fromString("0190e888-0000-7000-8000-000000000010");
    private static final UUID USER_B = UUID.fromString("0190e888-0000-7000-8000-000000000020");
    private static final AtomicLong MOVEMENT_SEQ = new AtomicLong(1);

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper mapper;

    private UUID agencyLocA;
    private UUID agencyLocB;
    private UUID packingLoc;
    private UUID unallocatedLoc;
    private UUID entityBLoc;

    @BeforeEach
    void setUp() {
        superuserJdbc().execute("TRUNCATE reporting.profit_center CASCADE");
        superuserJdbc().execute("TRUNCATE reporting.shop_sale_line_fact CASCADE");
        superuserJdbc().execute("TRUNCATE reporting.shop_sale_fact CASCADE");
        superuserJdbc().execute("TRUNCATE inventory.stock_movement CASCADE");
        superuserJdbc()
                .execute(
                        "DELETE FROM party.location WHERE owner_entity_id IN ('" + ENTITY_A + "', '" + ENTITY_B + "')");

        agencyLocA = Ids.next();
        agencyLocB = Ids.next();
        packingLoc = Ids.next();
        unallocatedLoc = Ids.next();
        entityBLoc = Ids.next();

        // Seed locations in party.location
        insertLocation(agencyLocA, ENTITY_A, "LOC-AG-A", "Agency A Location");
        insertLocation(agencyLocB, ENTITY_A, "LOC-AG-B", "Agency B Location");
        insertLocation(packingLoc, ENTITY_A, "LOC-PACK", "Packing Center");
        insertLocation(unallocatedLoc, ENTITY_A, "LOC-RETAIL", "General Retail Shop");
        insertLocation(entityBLoc, ENTITY_B, "LOC-ENT-B", "Entity B Location");
    }

    private void insertLocation(UUID locId, UUID entityId, String code, String name) {
        superuserJdbc()
                .update(
                        """
                INSERT INTO party.location (location_id, owner_entity_id, location_code, location_type, name_en)
                VALUES (?, ?, ?, 'SHOP', ?)
                """,
                        locId,
                        entityId,
                        code,
                        name);
    }

    @Test
    void createAndListProfitCenters() {
        HttpHeaders headers = asEntity(USER_A, ENTITY_A);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "code", "AG-01",
                "name", "Polgolla Agency A",
                "category", "AGENCY",
                "locationId", agencyLocA.toString());

        ResponseEntity<JsonNode> created = http.exchange(
                "/v1/reporting/profit-centers", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode resp = created.getBody();
        assertThat(resp).isNotNull();
        assertThat(resp.get("code").asText()).isEqualTo("AG-01");
        assertThat(resp.get("name").asText()).isEqualTo("Polgolla Agency A");
        assertThat(resp.get("category").asText()).isEqualTo("AGENCY");
        assertThat(resp.get("locationId").asText()).isEqualTo(agencyLocA.toString());

        // Verify audit and event
        assertThat(kernel.committedAudit()).extracting("eventType").contains("PROFIT_CENTER_CREATED");
        assertThat(kernel.committedEvents()).singleElement().isInstanceOf(ProfitCenterCreated.class);

        // List profit centers
        ResponseEntity<JsonNode> list = http.exchange(
                "/v1/reporting/profit-centers",
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = list.getBody().get("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("code").asText()).isEqualTo("AG-01");

        // Verify device/till caller is rejected (authorization)
        ResponseEntity<JsonNode> forbidden = http.exchange(
                "/v1/reporting/profit-centers",
                HttpMethod.GET,
                new HttpEntity<>(TestIdentityProvider.deviceHeaders(UUID.randomUUID())),
                JsonNode.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void separateAgenciesAndReconcileTotals() {
        // 1. Create Profit Centers: Agency A, Agency B, Packing
        UUID pcAgencyA = createProfitCenter("AG-01", "Agency A", "AGENCY", agencyLocA);
        UUID pcAgencyB = createProfitCenter("AG-02", "Agency B", "AGENCY", agencyLocB);
        UUID pcPacking = createProfitCenter("PACK-01", "Packing Ops", "PACKING", packingLoc);

        // 2. Insert sales facts for 2026-10:
        // Agency A: 2 transactions, net sales = 3,000.00
        insertSaleFact(agencyLocA, "2026-10-05", "RCP-A1", 2000.00, 200.00);
        insertSaleFact(agencyLocA, "2026-10-12", "RCP-A2", 1000.00, 100.00);

        // Agency B: 1 transaction, net sales = 1,500.00
        insertSaleFact(agencyLocB, "2026-10-15", "RCP-B1", 1500.00, 150.00);

        // Packing Ops: 0 sales (pure cost center / internal)

        // Unallocated shop: 1 transaction, net sales = 500.00
        insertSaleFact(unallocatedLoc, "2026-10-20", "RCP-UN1", 500.00, 50.00);

        // 3. Insert stock movements (COGS recognized):
        // Agency A: cost = 2,100.00 (qty = 10, cost = 210.00)
        insertStockSaleMovement(agencyLocA, "2026-10-05", 10.0, 210.00);

        // Agency B: cost = 1,050.00 (qty = 5, cost = 210.00)
        insertStockSaleMovement(agencyLocB, "2026-10-15", 5.0, 210.00);

        // Unallocated shop: cost = 350.00 (qty = 2, cost = 175.00)
        insertStockSaleMovement(unallocatedLoc, "2026-10-20", 2.0, 175.00);

        // 4. Query Monthly P&L
        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/profit-centers/monthly-pnl?month=2026-10",
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();

        // Summary Total
        JsonNode summary = body.get("summary");
        assertThat(summary.get("costPending").asBoolean()).isFalse();
        // Total revenue = 3000 + 1500 + 500 = 5000.00
        assertThat(new BigDecimal(summary.get("totalRevenue").asText())).isEqualByComparingTo("5000.00");
        // Total cost = 2100 + 1050 + 350 = 3500.00
        assertThat(new BigDecimal(summary.get("totalCost").asText())).isEqualByComparingTo("3500.00");
        // Gross profit = 5000 - 3500 = 1500.00
        assertThat(new BigDecimal(summary.get("grossProfit").asText())).isEqualByComparingTo("1500.00");
        // Margin = (1500 / 5000) * 100 = 30.00%
        assertThat(new BigDecimal(summary.get("marginPercent").asText())).isEqualByComparingTo("30.00");

        // Agency Breakdown Items
        JsonNode items = body.get("items");
        assertThat(items).hasSize(3);

        // Agency A
        JsonNode itemA = findItem(items, "AG-01");
        assertThat(itemA.get("txnCount").asInt()).isEqualTo(2);
        assertThat(new BigDecimal(itemA.get("revenue").asText())).isEqualByComparingTo("3000.00");
        assertThat(new BigDecimal(itemA.get("cost").asText())).isEqualByComparingTo("2100.00");
        assertThat(new BigDecimal(itemA.get("grossProfit").asText())).isEqualByComparingTo("900.00");
        assertThat(new BigDecimal(itemA.get("marginPercent").asText())).isEqualByComparingTo("30.00");
        assertThat(itemA.get("costPending").asBoolean()).isFalse();

        // Agency B
        JsonNode itemB = findItem(items, "AG-02");
        assertThat(itemB.get("txnCount").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(itemB.get("revenue").asText())).isEqualByComparingTo("1500.00");
        assertThat(new BigDecimal(itemB.get("cost").asText())).isEqualByComparingTo("1050.00");
        assertThat(new BigDecimal(itemB.get("grossProfit").asText())).isEqualByComparingTo("450.00");
        assertThat(new BigDecimal(itemB.get("marginPercent").asText())).isEqualByComparingTo("30.00");
        assertThat(itemB.get("costPending").asBoolean()).isFalse();

        // Packing
        JsonNode itemPack = findItem(items, "PACK-01");
        assertThat(itemPack.get("txnCount").asInt()).isEqualTo(0);
        assertThat(new BigDecimal(itemPack.get("revenue").asText())).isEqualByComparingTo("0.00");

        // Unallocated Row
        JsonNode unallocated = body.get("unallocated");
        assertThat(unallocated).isNotNull();
        assertThat(unallocated.get("txnCount").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(unallocated.get("revenue").asText())).isEqualByComparingTo("500.00");
        assertThat(new BigDecimal(unallocated.get("cost").asText())).isEqualByComparingTo("350.00");
        assertThat(new BigDecimal(unallocated.get("grossProfit").asText())).isEqualByComparingTo("150.00");

        // Combined Reconciliation Check: sum of items + unallocated strictly equals summary
        BigDecimal sumRevenue = new BigDecimal(itemA.get("revenue").asText())
                .add(new BigDecimal(itemB.get("revenue").asText()))
                .add(new BigDecimal(itemPack.get("revenue").asText()))
                .add(new BigDecimal(unallocated.get("revenue").asText()));
        assertThat(sumRevenue).isEqualByComparingTo(summary.get("totalRevenue").asText());
    }

    @Test
    void filterByProfitCenterIdReturnsSpecificAgency() {
        UUID pcAgencyA = createProfitCenter("AG-01", "Agency A", "AGENCY", agencyLocA);
        createProfitCenter("AG-02", "Agency B", "AGENCY", agencyLocB);

        insertSaleFact(agencyLocA, "2026-10-05", "RCP-A1", 2000.00, 200.00);
        insertSaleFact(agencyLocB, "2026-10-15", "RCP-B1", 1500.00, 150.00);

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/profit-centers/monthly-pnl?month=2026-10&profitCenterId=" + pcAgencyA,
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("items")).hasSize(1);
        assertThat(body.get("items").get(0).get("code").asText()).isEqualTo("AG-01");
        assertThat(body.get("unallocated").isNull()).isTrue();
    }

    @Test
    void surfacesPendingCostExplicitlyWithoutOverstatingProfit() {
        createProfitCenter("AG-01", "Agency A", "AGENCY", agencyLocA);

        // Sales fact present: 3,000.00 revenue
        insertSaleFact(agencyLocA, "2026-10-05", "RCP-A1", 3000.00, 300.00);

        // Stock movement has zero cost lot (unresolved valuation)
        insertStockSaleMovement(agencyLocA, "2026-10-05", 10.0, 0.00);

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/profit-centers/monthly-pnl?month=2026-10",
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();

        // Summary must flag costPending = true, cost/profit/margin MUST NOT be zero or overstated
        JsonNode summary = body.get("summary");
        assertThat(summary.get("costPending").asBoolean()).isTrue();
        assertThat(summary.get("totalCost").isNull()).isTrue();
        assertThat(summary.get("grossProfit").isNull()).isTrue();
        assertThat(summary.get("marginPercent").isNull()).isTrue();

        // Agency item must also flag costPending = true
        JsonNode itemA = body.get("items").get(0);
        assertThat(itemA.get("costPending").asBoolean()).isTrue();
        assertThat(itemA.get("cost").isNull()).isTrue();
        assertThat(itemA.get("grossProfit").isNull()).isTrue();
        assertThat(itemA.get("marginPercent").isNull()).isTrue();
    }

    @Test
    void crossEntityTenantIsolation() {
        // Entity A creates a profit center and has sales
        createProfitCenter("AG-A-01", "Entity A Agency", "AGENCY", agencyLocA);
        insertSaleFact(agencyLocA, "2026-10-05", "RCP-A1", 1000.00, 100.00);

        // Entity B requests profit centers
        ResponseEntity<JsonNode> listB = http.exchange(
                "/v1/reporting/profit-centers",
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_B, ENTITY_B)),
                JsonNode.class);

        assertThat(listB.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listB.getBody().get("items")).isEmpty();

        // Entity B requests P&L
        ResponseEntity<JsonNode> pnlB = http.exchange(
                "/v1/reporting/profit-centers/monthly-pnl?month=2026-10",
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_B, ENTITY_B)),
                JsonNode.class);

        assertThat(pnlB.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pnlB.getBody().get("items")).isEmpty();
        assertThat(new BigDecimal(
                        pnlB.getBody().get("summary").get("totalRevenue").asText()))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void invalidMonthFormatRefused() {
        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/profit-centers/monthly-pnl?month=invalid-date",
                HttpMethod.GET,
                new HttpEntity<>(asEntity(USER_A, ENTITY_A)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private UUID createProfitCenter(String code, String name, String category, UUID locationId) {
        HttpHeaders headers = asEntity(USER_A, ENTITY_A);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "code", code,
                "name", name,
                "category", category,
                "locationId", locationId != null ? locationId.toString() : "");

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/reporting/profit-centers", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().get("profitCenterId").asText());
    }

    private void insertSaleFact(UUID locId, String date, String docNo, double net, double tax) {
        UUID rcpId = Ids.next();
        superuserJdbc()
                .update(
                        """
                INSERT INTO reporting.shop_sale_fact
                       (receipt_id, owner_entity_id, location_id, business_date, doc_number, net, tax, gross, lines, occurred_at, event_id)
                VALUES (?, ?, ?, ?::date, ?, ?, ?, ?, 1, now(), ?)
                """,
                        rcpId,
                        ENTITY_A,
                        locId,
                        date,
                        docNo,
                        BigDecimal.valueOf(net),
                        BigDecimal.valueOf(tax),
                        BigDecimal.valueOf(net + tax),
                        Ids.next());
    }

    private void insertStockSaleMovement(UUID locId, String date, double qty, double unitCost) {
        superuserJdbc()
                .update(
                        """
                INSERT INTO inventory.stock_movement
                       (movement_id, received_at, source, movement_seq, owner_entity_id, location_id,
                        batch_id, sku_id, condition, qty_delta, movement_type, unit_cost_at_movement,
                        document_id, occurred_at)
                VALUES (?, now(), 'central', ?, ?, ?,
                        ?, ?, 'GOOD', ?, 'SALE', ?,
                        ?, (? || ' 12:00:00+05:30')::timestamptz)
                """,
                        Ids.next(),
                        MOVEMENT_SEQ.getAndIncrement(),
                        ENTITY_A,
                        locId,
                        Ids.next(),
                        Ids.next(),
                        BigDecimal.valueOf(-qty),
                        BigDecimal.valueOf(unitCost),
                        Ids.next(),
                        date);
    }

    private static JsonNode findItem(JsonNode items, String code) {
        for (JsonNode item : items) {
            if (code.equals(item.get("code").asText())) {
                return item;
            }
        }
        throw new AssertionError("Profit center with code " + code + " not found in items: " + items);
    }

    private static HttpHeaders asEntity(UUID user, UUID entity) {
        return TestIdentityProvider.entityWideHeaders(user, entity);
    }
}
