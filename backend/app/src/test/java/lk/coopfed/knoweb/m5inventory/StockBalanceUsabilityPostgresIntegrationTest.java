package lk.coopfed.knoweb.m5inventory;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.StockExplanationView;
import lk.coopfed.knoweb.m5inventory.query.StockSummaryView;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class StockBalanceUsabilityPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e676-0000-7000-8000-000000000002");
    private static final UUID OTHER = UUID.fromString("0190e676-0000-7000-8000-000000000003");

    @Autowired
    InventoryQueries queries;

    @Autowired
    StockLedger ledger;

    @Autowired
    OuterCommand outer;

    @Autowired
    JdbcTemplate jdbc;

    private InventoryFixture fixture;
    private UUID warehouse;
    private UUID shop;
    private UUID sku;
    private UUID batch;

    @BeforeEach
    void setUp() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        warehouse = fixture.location(MPCS, "WAREHOUSE");
        shop = fixture.location(MPCS, "SHOP");
        sku = fixture.sku(MPCS, "RICE5");
        batch = fixture.batch(sku, MPCS, "BAT-01", LocalDate.of(2027, 12, 31));
    }

    @AfterEach
    void tearDown() {
        fixture.clean();
    }

    private void post(ScopeContext scope, Movement... movements) {
        outer.run(scope, () -> ledger.post(new PostMovements(Ids.next(), null, null, List.of(movements)), scope));
    }

    @Test
    void stockSummaryAggregatesOnHandAndEnforcesTenantIsolation() {
        Movement receipt = new Movement(
                warehouse,
                batch,
                LotCondition.GOOD,
                MovementType.RECEIPT,
                new BigDecimal("100.000"),
                new BigDecimal("150.0000"),
                null);

        post(own(MPCS), receipt);

        // When queried within entity's scope
        List<StockSummaryView> summaries = queries.stockSummaries(warehouse, null, null, null, true, false, own(MPCS));

        assertThat(summaries).isNotEmpty();
        StockSummaryView summary = summaries.stream()
                .filter(s -> s.skuId().equals(sku))
                .findFirst()
                .orElseThrow();

        assertThat(summary.qtyOnHand()).isEqualByComparingTo("100.000");
        assertThat(summary.reservedQty()).isEqualByComparingTo("0.000");
        assertThat(summary.availableQty()).isEqualByComparingTo("100.000");
        assertThat(summary.remainingStockQty()).isEqualByComparingTo("100.000");

        // When queried with another entity's scope, tenant isolation blocks visibility
        List<StockSummaryView> otherSummaries =
                queries.stockSummaries(warehouse, null, null, null, true, false, own(OTHER));
        assertThat(otherSummaries).isEmpty();
    }

    @Test
    void stockExplanationProvidesLotBreakdown() {
        Movement receipt = new Movement(
                warehouse,
                batch,
                LotCondition.GOOD,
                MovementType.RECEIPT,
                new BigDecimal("60.000"),
                new BigDecimal("120.0000"),
                null);

        post(own(MPCS), receipt);

        Optional<StockExplanationView> explanationOpt = queries.stockExplanation(warehouse, sku, own(MPCS));

        assertThat(explanationOpt).isPresent();
        StockExplanationView explanation = explanationOpt.get();
        assertThat(explanation.skuId()).isEqualTo(sku);
        assertThat(explanation.qtyOnHand()).isEqualByComparingTo("60.000");
        assertThat(explanation.availableQty()).isEqualByComparingTo("60.000");
        assertThat(explanation.lots()).hasSize(1);
        assertThat(explanation.lots().get(0).batchId()).isEqualTo(batch);
        assertThat(explanation.lots().get(0).condition()).isEqualTo("GOOD");
        assertThat(explanation.lots().get(0).qtyOnHand()).isEqualByComparingTo("60.000");
        assertThat(explanation.reservations()).isEmpty();
        assertThat(explanation.incomingTransfers()).isEmpty();
        assertThat(explanation.outgoingTransfers()).isEmpty();
    }
}
