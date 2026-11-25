package lk.coopfed.knoweb.m6pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m6pos.query.PosQueries;
import lk.coopfed.knoweb.m6pos.query.PosQueries.ReceiptProfitView;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class ReceiptProfitIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e6a0-0000-7000-8000-000000000002");
    private static final UUID OTHER_ENTITY = UUID.fromString("0190e6a0-0000-7000-8000-000000000003");
    private static final UUID SHOP = UUID.fromString("0190e6a0-0000-7000-8000-000000000101");
    private static final UUID USER = UUID.fromString("0190e6a0-0000-7000-8000-000000000099");

    @Autowired
    private PosQueries posQueries;

    @Autowired
    private TestRestTemplate http;

    @BeforeEach
    void clean() {
        JdbcTemplate db = superuserJdbc();
        db.update("delete from pos.receipt_line");
        db.update("delete from pos.receipt_tender");
        db.update("delete from pos.receipt");
        db.update("delete from inventory.stock_movement");
        db.update("delete from catalogue.sku_barcode");
        db.update("delete from catalogue.sku where sku_code in ('SKU-PROFIT-1', 'SKU-PROFIT-2')");

        db.update(
                """
                insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en, status)
                values (?, ?, 'SHOP-PROF', 'SHOP', 'Profit Test Shop', 'ACTIVE')
                on conflict (location_id) do nothing
                """,
                SHOP,
                MPCS);
    }

    @Test
    void receiptProfitCalculatesAccuratelyFromAuthoritativeM5Cost() {
        JdbcTemplate db = superuserJdbc();
        UUID sku1 = insertSku(db, "SKU-PROFIT-1");
        UUID sku2 = insertSku(db, "SKU-PROFIT-2");
        UUID docId = Ids.next();
        UUID batch1 = Ids.next();
        UUID batch2 = Ids.next();

        // Receipt: Gross 500.00, Net 450.00 (Rs 50 discount across the basket)
        // Line 1: total 300.00 (allocated net = 300 * 450 / 500 = 270.00)
        // Line 2: total 200.00 (allocated net = 200 * 450 / 500 = 180.00)
        insertReceipt(db, docId, SHOP, MPCS, new BigDecimal("500.00"), new BigDecimal("450.00"));
        insertReceiptLine(
                db,
                docId,
                SHOP,
                1,
                sku1,
                batch1,
                new BigDecimal("2.000"),
                new BigDecimal("150.00"),
                new BigDecimal("300.00"),
                MPCS);
        insertReceiptLine(
                db,
                docId,
                SHOP,
                2,
                sku2,
                batch2,
                new BigDecimal("1.000"),
                new BigDecimal("200.00"),
                new BigDecimal("200.00"),
                MPCS);

        // M5 stock movements recorded at sale time:
        // SKU 1 unit cost: 100.0000 -> line cost: 200.00 -> line profit: 270.00 - 200.00 = 70.00 -> margin: 70/270 =
        // 25.93%
        // SKU 2 unit cost: 120.0000 -> line cost: 120.00 -> line profit: 180.00 - 120.00 = 60.00 -> margin: 60/180 =
        // 33.33%
        insertMovement(
                db, docId, sku1, batch1, SHOP, MPCS, "SALE", new BigDecimal("-2.000"), new BigDecimal("100.0000"));
        insertMovement(
                db, docId, sku2, batch2, SHOP, MPCS, "SALE", new BigDecimal("-1.000"), new BigDecimal("120.0000"));

        ScopeContext scope = ownScope(MPCS);
        Optional<ReceiptProfitView> result = posQueries.receiptProfit(docId, scope);

        assertThat(result).isPresent();
        ReceiptProfitView profit = result.get();
        assertThat(profit.costPending()).isFalse();
        assertThat(profit.netSales()).isEqualByComparingTo("450.00");
        assertThat(profit.totalCost()).isEqualByComparingTo("320.00");
        assertThat(profit.grossProfit()).isEqualByComparingTo("130.00");
        assertThat(profit.marginPercent()).isEqualByComparingTo("28.89");
        assertThat(profit.lines()).hasSize(2);

        ReceiptProfitView.LineProfitView l1 = profit.lines().get(0);
        assertThat(l1.lineNo()).isEqualTo(1);
        assertThat(l1.costPending()).isFalse();
        assertThat(l1.lineNet()).isEqualByComparingTo("270.00");
        assertThat(l1.unitCost()).isEqualByComparingTo("100.0000");
        assertThat(l1.lineCost()).isEqualByComparingTo("200.00");
        assertThat(l1.lineProfit()).isEqualByComparingTo("70.00");
        assertThat(l1.marginPercent()).isEqualByComparingTo("25.93");

        ReceiptProfitView.LineProfitView l2 = profit.lines().get(1);
        assertThat(l2.lineNo()).isEqualTo(2);
        assertThat(l2.costPending()).isFalse();
        assertThat(l2.lineNet()).isEqualByComparingTo("180.00");
        assertThat(l2.unitCost()).isEqualByComparingTo("120.0000");
        assertThat(l2.lineCost()).isEqualByComparingTo("120.00");
        assertThat(l2.lineProfit()).isEqualByComparingTo("60.00");
        assertThat(l2.marginPercent()).isEqualByComparingTo("33.33");

        // HTTP endpoint test
        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/pos/receipts/" + docId + "/profit",
                HttpMethod.GET,
                new HttpEntity<>(TestIdentityProvider.entityWideHeaders(USER, MPCS)),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("costPending").asBoolean()).isFalse();
        assertThat(new BigDecimal(body.get("netSales").asText())).isEqualByComparingTo("450.00");
        assertThat(new BigDecimal(body.get("totalCost").asText())).isEqualByComparingTo("320.00");
        assertThat(new BigDecimal(body.get("grossProfit").asText())).isEqualByComparingTo("130.00");
        assertThat(new BigDecimal(body.get("marginPercent").asText())).isEqualByComparingTo("28.89");
    }

    @Test
    void receiptProfitMarksPendingAndDoesNotFakeZeroCostWhenCostIsMissingOrOversold() {
        JdbcTemplate db = superuserJdbc();
        UUID sku1 = insertSku(db, "SKU-PROFIT-1");
        UUID sku2 = insertSku(db, "SKU-PROFIT-2");
        UUID docId = Ids.next();

        insertReceipt(db, docId, SHOP, MPCS, new BigDecimal("500.00"), new BigDecimal("500.00"));
        insertReceiptLine(
                db,
                docId,
                SHOP,
                1,
                sku1,
                Ids.next(),
                new BigDecimal("1.000"),
                new BigDecimal("250.00"),
                new BigDecimal("250.00"),
                MPCS);
        insertReceiptLine(
                db,
                docId,
                SHOP,
                2,
                sku2,
                Ids.next(),
                new BigDecimal("1.000"),
                new BigDecimal("250.00"),
                new BigDecimal("250.00"),
                MPCS);

        // SKU 1 has known cost
        insertMovement(
                db, docId, sku1, Ids.next(), SHOP, MPCS, "SALE", new BigDecimal("-1.000"), new BigDecimal("180.0000"));
        // SKU 2 was oversold without cost: unit_cost is 0.0000 or missing
        insertMovement(db, docId, sku2, Ids.next(), SHOP, MPCS, "SALE", new BigDecimal("-1.000"), BigDecimal.ZERO);

        ScopeContext scope = ownScope(MPCS);
        Optional<ReceiptProfitView> result = posQueries.receiptProfit(docId, scope);

        assertThat(result).isPresent();
        ReceiptProfitView profit = result.get();
        // Crucial invariant: missing/oversold cost must NOT fake 0 cost and must flag costPending
        assertThat(profit.costPending()).isTrue();
        assertThat(profit.totalCost()).isNull();
        assertThat(profit.grossProfit()).isNull();
        assertThat(profit.marginPercent()).isNull();

        assertThat(profit.lines().get(0).costPending()).isFalse();
        assertThat(profit.lines().get(0).lineCost()).isEqualByComparingTo("180.00");
        assertThat(profit.lines().get(0).lineProfit()).isEqualByComparingTo("70.00");

        assertThat(profit.lines().get(1).costPending()).isTrue();
        assertThat(profit.lines().get(1).unitCost()).isNull();
        assertThat(profit.lines().get(1).lineCost()).isNull();
        assertThat(profit.lines().get(1).lineProfit()).isNull();
        assertThat(profit.lines().get(1).marginPercent()).isNull();
    }

    @Test
    void receiptProfitHiddenAcrossEntitiesByRowLevelSecurity() {
        JdbcTemplate db = superuserJdbc();
        UUID docId = Ids.next();
        insertReceipt(db, docId, SHOP, MPCS, new BigDecimal("100.00"), new BigDecimal("100.00"));

        // Caller from another entity cannot see the receipt
        ScopeContext otherScope = ownScope(OTHER_ENTITY);
        Optional<ReceiptProfitView> result = posQueries.receiptProfit(docId, otherScope);
        assertThat(result).isEmpty();

        ResponseEntity<JsonNode> response = http.exchange(
                "/v1/pos/receipts/" + docId + "/profit",
                HttpMethod.GET,
                new HttpEntity<>(TestIdentityProvider.entityWideHeaders(USER, OTHER_ENTITY)),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static ScopeContext ownScope(UUID entity) {
        return new ScopeContext(
                USER,
                null,
                entity,
                List.of(new Scope(entity, null)),
                new Scope(entity, null),
                PolicyClass.OWN,
                java.util.Set.of(),
                Instant.now(),
                java.util.Locale.ENGLISH,
                null);
    }

    private static UUID insertSku(JdbcTemplate admin, String code) {
        UUID skuId = Ids.next();
        UUID taxCat = UUID.fromString("0190e6a0-0000-7000-8000-000000000010");
        admin.update(
                """
                insert into catalogue.sku (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si,
                                           short_name_ta, base_uom_code, sold_by_weight, has_printed_mrp, tax_category_id)
                values (?, ?, ?, 'SHARED', ?, ?, ?, 'EA', false, true, ?)
                """,
                skuId,
                code,
                MPCS,
                code,
                code,
                code,
                taxCat);
        return skuId;
    }

    private static void insertReceipt(
            JdbcTemplate admin, UUID docId, UUID locId, UUID entityId, BigDecimal gross, BigDecimal net) {
        admin.update(
                """
                insert into pos.receipt (document_id, location_id, device_id, session_id, doc_number_display,
                                         issued_at, business_date, gross_amount, net_amount, tax_amount, flags, owner_entity_id)
                values (?, ?, ?, ?, 'REC-001', now(), current_date, ?, ?, 0.00, '{}', ?)
                """,
                docId,
                locId,
                Ids.next(),
                Ids.next(),
                gross,
                net,
                entityId);
    }

    private static void insertReceiptLine(
            JdbcTemplate admin,
            UUID docId,
            UUID locId,
            int lineNo,
            UUID skuId,
            UUID batchId,
            BigDecimal qty,
            BigDecimal price,
            BigDecimal total,
            UUID entityId) {
        admin.update(
                """
                insert into pos.receipt_line (document_id, line_no, sku_id, batch_id, qty, unit_price, line_total, location_id, owner_entity_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                docId,
                lineNo,
                skuId,
                batchId,
                qty,
                price,
                total,
                locId,
                entityId);
    }

    private static long nextSeq = 1;

    private static void insertMovement(
            JdbcTemplate admin,
            UUID docId,
            UUID skuId,
            UUID batchId,
            UUID locId,
            UUID entityId,
            String type,
            BigDecimal qtyDelta,
            BigDecimal unitCost) {
        admin.update(
                """
                insert into inventory.stock_movement (movement_id, owner_entity_id, location_id, batch_id, sku_id,
                                                      movement_type, qty_delta, unit_cost_at_movement, document_id,
                                                      condition, source, movement_seq, occurred_at, received_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'GOOD', 'central', ?, now(), now())
                """,
                Ids.next(),
                entityId,
                locId,
                batchId,
                skuId,
                type,
                qtyDelta,
                unitCost,
                docId,
                nextSeq++);
    }
}
