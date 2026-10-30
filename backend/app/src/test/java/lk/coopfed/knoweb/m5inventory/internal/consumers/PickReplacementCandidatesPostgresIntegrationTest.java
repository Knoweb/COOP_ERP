package lk.coopfed.knoweb.m5inventory.internal.consumers;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.system;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.PickListView;
import lk.coopfed.knoweb.testsupport.OuterCommand;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Permission and disclosure proof for the narrow OverridePick candidate read. */
class PickReplacementCandidatesPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID OWNER = TEST_FEDERATION;

    @DynamicPropertySource
    static void enforcePermissions(DynamicPropertyRegistry registry) {
        registry.add("coop-erp.security.enforce-permissions", () -> "true");
    }

    @Autowired
    Handles<ReserveDelivery, UUID> reserve;

    @Autowired
    StockLedger ledger;

    @Autowired
    InventoryQueries queries;

    @Autowired
    OuterCommand outer;

    @Autowired
    TestRestTemplate http;

    private InventoryFixture fixture;
    private UUID warehouse;
    private UUID otherWarehouse;
    private UUID sku;
    private UUID otherSku;
    private UUID late;

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        warehouse = fixture.location(OWNER, "WAREHOUSE");
        otherWarehouse = fixture.location(OWNER, "WAREHOUSE");
        sku = fixture.sku(OWNER, "MILK");
        otherSku = fixture.sku(OWNER, "SOAP");
        UUID early = fixture.batch(sku, OWNER, "EARLY", LocalDate.of(2027, 3, 31));
        late = fixture.batch(sku, OWNER, "LATE", LocalDate.of(2027, 9, 30));
        post(warehouse, early, "10", "900", LotCondition.GOOD);
        post(warehouse, late, "10", "950", LotCondition.GOOD);
        kernel.reset();
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void candidateReadNeedsOnlyWhsPickAndDoesNotExposeIneligibleOrSiblingLocationLots() {
        Pick pick = openPick("4");
        UUID lateLot = lot(warehouse, late);
        UUID damagedBatch = fixture.batch(sku, OWNER, "CAND-DAMAGED", LocalDate.of(2027, 8, 1));
        post(warehouse, damagedBatch, "10", "800", LotCondition.DAMAGED);
        UUID expiredBatch = fixture.batch(sku, OWNER, "CAND-EXPIRED", LocalDate.of(2025, 1, 1));
        post(warehouse, expiredBatch, "10", "800", LotCondition.GOOD);
        UUID otherSkuBatch = fixture.batch(otherSku, OWNER, "CAND-OTHER-SKU", LocalDate.of(2027, 8, 1));
        post(warehouse, otherSkuBatch, "10", "800", LotCondition.GOOD);
        UUID shortBatch = fixture.batch(sku, OWNER, "CAND-SHORT", LocalDate.of(2027, 8, 1));
        post(warehouse, shortBatch, "3", "800", LotCondition.GOOD);
        UUID reservedBatch = fixture.batch(sku, OWNER, "CAND-RESERVED", LocalDate.of(2027, 10, 1));
        post(warehouse, reservedBatch, "10", "800", LotCondition.GOOD);
        reserve.handle(
                new ReserveDelivery(
                        Ids.next(),
                        OWNER,
                        List.of(new ReserveDelivery.Line(Ids.next(), sku, reservedBatch, new BigDecimal("7")))),
                system(OWNER));
        post(otherWarehouse, late, "10", "950", LotCondition.GOOD);

        UUID picker = fixture.userWith(OWNER, "whs.pick", null);
        HttpHeaders pickerHeaders = TestIdentityProvider.headers(picker, OWNER);
        String url = "/v1/inventory/pick-lists/" + pick.deliveryNoteId() + "/lines/" + pick.pickLineId()
                + "/replacement-candidates";

        ResponseEntity<List> candidates =
                http.exchange(url, HttpMethod.GET, new HttpEntity<>(pickerHeaders), List.class);
        assertThat(candidates.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(candidates.getBody()).singleElement().satisfies(row -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> candidate = (Map<String, Object>) row;
            assertThat(candidate.get("stockLotId")).isEqualTo(lateLot.toString());
            assertThat(candidate.get("batchId")).isEqualTo(late.toString());
            assertThat(candidate.get("batchNo").toString()).startsWith("LATE");
            assertThat(new BigDecimal(candidate.get("availableQty").toString())).isEqualByComparingTo("10");
            assertThat(candidate).doesNotContainKeys("skuId", "locationId", "unitCost", "qtyOnHand");
        });

        assertThat(http.exchange(
                                "/v1/inventory/locations/" + warehouse + "/balances",
                                HttpMethod.GET,
                                new HttpEntity<>(pickerHeaders),
                                String.class)
                        .getStatusCode())
                .as("whs.pick does not imply inv.stock.view")
                .isEqualTo(HttpStatus.FORBIDDEN);

        UUID stockViewer = fixture.userWith(OWNER, "inv.stock.view", null);
        assertThat(http.exchange(
                                url,
                                HttpMethod.GET,
                                new HttpEntity<>(TestIdentityProvider.headers(stockViewer, OWNER)),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        HttpHeaders siblingScope = TestIdentityProvider.headers(picker, OWNER);
        siblingScope.set("X-Scope-Location", otherWarehouse.toString());
        ResponseEntity<List> sibling = http.exchange(url, HttpMethod.GET, new HttpEntity<>(siblingScope), List.class);
        assertThat(sibling.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sibling.getBody()).isEmpty();
    }

    private Pick openPick(String qty) {
        UUID delivery = Ids.next();
        reserve.handle(
                new ReserveDelivery(
                        delivery, OWNER, List.of(new ReserveDelivery.Line(Ids.next(), sku, null, new BigDecimal(qty)))),
                system(OWNER));
        PickListView.Pick line =
                queries.pickList(delivery, system(OWNER)).orElseThrow().lines().get(0);
        kernel.reset();
        return new Pick(delivery, line.pickLineId());
    }

    private void post(UUID location, UUID batch, String qty, String cost, LotCondition condition) {
        ScopeContext scope = system(OWNER);
        outer.run(
                scope,
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(new Movement(
                                        location,
                                        batch,
                                        condition,
                                        MovementType.RECEIPT,
                                        new BigDecimal(qty),
                                        new BigDecimal(cost),
                                        null))),
                        scope));
    }

    private UUID lot(UUID location, UUID batch) {
        return superuserJdbc()
                .queryForObject(
                        "select stock_lot_id from inventory.stock_lot"
                                + " where location_id = ? and batch_id = ? and condition = 'GOOD'",
                        UUID.class,
                        location,
                        batch);
    }

    private record Pick(UUID deliveryNoteId, UUID pickLineId) {}
}
