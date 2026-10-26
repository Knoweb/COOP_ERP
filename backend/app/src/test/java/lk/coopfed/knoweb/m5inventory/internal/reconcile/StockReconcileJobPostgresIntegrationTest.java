package lk.coopfed.knoweb.m5inventory.internal.reconcile;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditReviewReader;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests for StockReconcileJob (doc 18 invariant B-I4; doc 25 sections 3.2, 10; 25A section 7.2; INV-03).
 */
class StockReconcileJobPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e67c-0000-7000-8000-000000000002");
    private static final UUID OTHER_ENTITY = UUID.fromString("0190e67c-0000-7000-8000-000000000003");
    private static final LocalDate EXPIRY = LocalDate.of(2027, 12, 31);

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StockReconcileJob job;

    @Autowired
    StockReconcileStore store;

    @Autowired
    StockLedger ledger;

    @Autowired
    AuditReviewReader auditReviews;

    @Autowired
    OuterCommand outer;

    private InventoryFixture fixture;
    private UUID shop1;
    private UUID shop2;
    private UUID otherShop;

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        fixture.entity(MPCS, "M5RC", "MPCS");
        fixture.entity(OTHER_ENTITY, "M5OT", "MPCS");
        shop1 = fixture.location(MPCS, "SHOP");
        shop2 = fixture.location(MPCS, "SHOP");
        otherShop = fixture.location(OTHER_ENTITY, "SHOP");
        kernel.reset();
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void consistentStockBalancesCreateNoReview() {
        UUID sku = fixture.sku(MPCS, "RICE_CLEAN");
        UUID batch = fixture.batch(sku, MPCS, "B-CLN-1", EXPIRY);
        postGood(shop1, batch, "30.000", "120.00", MPCS);
        postGood(shop2, batch, "20.000", "120.00", MPCS);
        kernel.reset();

        int processed = job.reconcile(execution());
        assertThat(processed).isEqualTo(0);

        assertThat(audits(StockReconcileJob.AUDIT_STOCK_DRIFT)).isEmpty();
    }

    @Test
    void plantedPositiveDriftCreatesOneReviewWithCorrectFigures() {
        UUID sku = fixture.sku(MPCS, "FLOUR_POS");
        UUID batch = fixture.batch(sku, MPCS, "B-POS-1", EXPIRY);
        postGood(shop1, batch, "10.000", "150.00", MPCS);

        UUID lotId = getLotId(shop1, batch, "GOOD");

        // Plant positive drift: qty_on_hand becomes 15.000 while sum of movements is 10.000
        plantDrift(lotId, new BigDecimal("15.000"), MPCS);
        kernel.reset();

        int processed = job.reconcile(execution());
        assertThat(processed).isEqualTo(1);

        List<KernelRecorder.AuditRecord> driftAudits = audits(StockReconcileJob.AUDIT_STOCK_DRIFT);
        assertThat(driftAudits).hasSize(1);
        KernelRecorder.AuditRecord record = driftAudits.get(0);
        assertThat(record.eventType()).isEqualTo(StockReconcileJob.AUDIT_STOCK_DRIFT);
        assertThat(record.subject().id()).isEqualTo(lotId);
        assertThat(record.subject().type()).isEqualTo("stock_lot");

        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) record.after();
        assertThat(after.get("stockLotId")).isEqualTo(lotId.toString());
        assertThat(after.get("locationId")).isEqualTo(shop1.toString());
        assertThat(after.get("batchId")).isEqualTo(batch.toString());
        assertThat(after.get("skuId")).isEqualTo(sku.toString());
        assertThat(after.get("condition")).isEqualTo("GOOD");
        assertThat(after.get("qtyOnHand")).isEqualTo("15.000");
        assertThat(after.get("movementSum")).isEqualTo("10.000");
        assertThat(after.get("drift")).isEqualTo("5.000");
    }

    @Test
    void plantedNegativeDriftCreatesReview() {
        UUID sku = fixture.sku(MPCS, "SUGAR_NEG");
        UUID batch = fixture.batch(sku, MPCS, "B-NEG-1", EXPIRY);
        postGood(shop1, batch, "25.000", "200.00", MPCS);

        UUID lotId = getLotId(shop1, batch, "GOOD");

        // Plant negative drift: qty_on_hand becomes 18.000 while sum of movements is 25.000
        plantDrift(lotId, new BigDecimal("18.000"), MPCS);
        kernel.reset();

        int processed = job.reconcile(execution());
        assertThat(processed).isEqualTo(1);

        List<KernelRecorder.AuditRecord> driftAudits = audits(StockReconcileJob.AUDIT_STOCK_DRIFT);
        assertThat(driftAudits).hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) driftAudits.get(0).after();
        assertThat(after.get("qtyOnHand")).isEqualTo("18.000");
        assertThat(after.get("movementSum")).isEqualTo("25.000");
        assertThat(after.get("drift")).isEqualTo("-7.000");
    }

    @Test
    void repeatedExecutionsDoNotDuplicateUnresolvedReviews() {
        UUID sku = fixture.sku(MPCS, "OIL_REP");
        UUID batch = fixture.batch(sku, MPCS, "B-REP-1", EXPIRY);
        postGood(shop1, batch, "40.000", "500.00", MPCS);

        UUID lotId = getLotId(shop1, batch, "GOOD");
        plantDrift(lotId, new BigDecimal("45.000"), MPCS);
        kernel.reset();

        // Run 1: detects drift and raises review
        int run1 = job.reconcile(execution());
        assertThat(run1).isEqualTo(1);
        assertThat(audits(StockReconcileJob.AUDIT_STOCK_DRIFT)).hasSize(1);

        // Run 2: same drift remains; deduplication via reviewExists skips creating duplicate review
        int run2 = job.reconcile(execution());
        assertThat(run2).isEqualTo(0);
        assertThat(audits(StockReconcileJob.AUDIT_STOCK_DRIFT)).hasSize(1);
    }

    @Test
    void multipleEntitiesAndLocationsStayIsolated() {
        UUID sku1 = fixture.sku(MPCS, "TEA_MPCS");
        UUID batch1 = fixture.batch(sku1, MPCS, "B-TEA-1", EXPIRY);
        postGood(shop1, batch1, "12.000", "350.00", MPCS);
        UUID lotIdMpcs = getLotId(shop1, batch1, "GOOD");
        plantDrift(lotIdMpcs, new BigDecimal("15.000"), MPCS); // +3.000 drift

        UUID sku2 = fixture.sku(OTHER_ENTITY, "COFFEE_OTHER");
        UUID batch2 = fixture.batch(sku2, OTHER_ENTITY, "B-COF-2", EXPIRY);
        postGood(otherShop, batch2, "8.000", "450.00", OTHER_ENTITY);
        UUID lotIdOther = getLotId(otherShop, batch2, "GOOD");
        plantDrift(lotIdOther, new BigDecimal("6.000"), OTHER_ENTITY); // -2.000 drift

        kernel.reset();

        int processed = job.reconcile(execution());
        assertThat(processed).isEqualTo(2);

        List<KernelRecorder.AuditRecord> reviews = audits(StockReconcileJob.AUDIT_STOCK_DRIFT);
        assertThat(reviews).hasSize(2);

        KernelRecorder.AuditRecord mpcsAudit = reviews.stream()
                .filter(r -> r.subject().id().equals(lotIdMpcs))
                .findFirst()
                .orElseThrow();
        assertThat(mpcsAudit.scope().entityId()).isEqualTo(MPCS);

        KernelRecorder.AuditRecord otherAudit = reviews.stream()
                .filter(r -> r.subject().id().equals(lotIdOther))
                .findFirst()
                .orElseThrow();
        assertThat(otherAudit.scope().entityId()).isEqualTo(OTHER_ENTITY);
    }

    @Test
    void databaseRlsEnforcesEntityIsolation() {
        UUID sku = fixture.sku(MPCS, "DHAL_RLS");
        UUID batch = fixture.batch(sku, MPCS, "B-DHL-1", EXPIRY);
        postGood(shop1, batch, "50.000", "180.00", MPCS);
        UUID lotId = getLotId(shop1, batch, "GOOD");
        plantDrift(lotId, new BigDecimal("55.000"), MPCS);

        job.reconcile(execution());

        // Under MPCS own scope: review exists
        boolean existsUnderMpcs = outer.run(
                own(MPCS),
                () -> auditReviews.reviewExists(
                        StockReconcileJob.AUDIT_STOCK_DRIFT, lotId, "drift", "5.000", own(MPCS)));
        assertThat(existsUnderMpcs).isTrue();

        // Under OTHER_ENTITY own scope: review does NOT exist due to RLS
        boolean existsUnderOther = outer.run(
                own(OTHER_ENTITY),
                () -> auditReviews.reviewExists(
                        StockReconcileJob.AUDIT_STOCK_DRIFT, lotId, "drift", "5.000", own(OTHER_ENTITY)));
        assertThat(existsUnderOther).isFalse();
    }

    @Test
    void matchingDifferentLotsCannotIncorrectlyCombineBalances() {
        UUID sku = fixture.sku(MPCS, "BISCUIT_COND");
        UUID batch = fixture.batch(sku, MPCS, "B-BSC-1", EXPIRY);

        // Same location, same batch, but GOOD vs DAMAGED condition
        postGood(shop1, batch, "20.000", "90.00", MPCS);
        postDamaged(shop1, batch, "5.000", "90.00", MPCS);

        UUID goodLotId = getLotId(shop1, batch, "GOOD");
        UUID damagedLotId = getLotId(shop1, batch, "DAMAGED");

        // Plant drift ONLY on the GOOD lot (+4.000), DAMAGED lot remains perfectly balanced
        plantDrift(goodLotId, new BigDecimal("24.000"), MPCS);
        kernel.reset();

        int processed = job.reconcile(execution());
        assertThat(processed).isEqualTo(1);

        List<KernelRecorder.AuditRecord> reviews = audits(StockReconcileJob.AUDIT_STOCK_DRIFT);
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).subject().id()).isEqualTo(goodLotId);

        // Damaged lot was not reported
        boolean damagedDriftExists = outer.run(
                own(MPCS),
                () -> auditReviews.reviewExists(
                        StockReconcileJob.AUDIT_STOCK_DRIFT, damagedLotId, null, null, own(MPCS)));
        assertThat(damagedDriftExists).isFalse();
    }

    @Test
    void noStockBalancesOrLedgerMovementsAreChangedByReconciliation() {
        UUID sku = fixture.sku(MPCS, "SOAP_IMMUTABLE");
        UUID batch = fixture.batch(sku, MPCS, "B-SOP-1", EXPIRY);
        postGood(shop1, batch, "100.000", "85.00", MPCS);

        UUID lotId = getLotId(shop1, batch, "GOOD");
        plantDrift(lotId, new BigDecimal("105.000"), MPCS);

        // Take snapshot of lot and movements before reconcile
        BigDecimal lotQtyBefore = getLotQty(lotId);
        int movementCountBefore = countMovements(shop1, batch);

        job.reconcile(execution());

        // Assert strictly unchanged: job raises review, never fixes or rewrites data
        BigDecimal lotQtyAfter = getLotQty(lotId);
        int movementCountAfter = countMovements(shop1, batch);

        assertThat(lotQtyAfter).isEqualByComparingTo(lotQtyBefore);
        assertThat(movementCountAfter).isEqualTo(movementCountBefore);
    }

    @Test
    void reconcileSingleLocationUnderOwnScope() {
        UUID sku = fixture.sku(MPCS, "SALT_LOC");
        UUID batch = fixture.batch(sku, MPCS, "B-SLT-1", EXPIRY);
        postGood(shop1, batch, "10.000", "50.00", MPCS);
        postGood(shop2, batch, "20.000", "50.00", MPCS);

        UUID lot1 = getLotId(shop1, batch, "GOOD");
        UUID lot2 = getLotId(shop2, batch, "GOOD");

        plantDrift(lot1, new BigDecimal("12.000"), MPCS);
        plantDrift(lot2, new BigDecimal("25.000"), MPCS);
        kernel.reset();

        // Reconcile shop1 only
        int processed = job.reconcileLocation(shop1, own(MPCS));
        assertThat(processed).isEqualTo(1);

        List<KernelRecorder.AuditRecord> reviews = audits(StockReconcileJob.AUDIT_STOCK_DRIFT);
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).subject().id()).isEqualTo(lot1);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private void postGood(UUID location, UUID batch, String qty, String cost, UUID entity) {
        ScopeContext scope = own(entity);
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
                                        LotCondition.GOOD,
                                        MovementType.RECEIPT,
                                        new BigDecimal(qty),
                                        new BigDecimal(cost),
                                        null))),
                        scope));
    }

    private void postDamaged(UUID location, UUID batch, String qty, String cost, UUID entity) {
        ScopeContext scope = own(entity);
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
                                        LotCondition.DAMAGED,
                                        MovementType.RECEIPT,
                                        new BigDecimal(qty),
                                        new BigDecimal(cost),
                                        null))),
                        scope));
    }

    private UUID getLotId(UUID locationId, UUID batchId, String condition) {
        return superuserJdbc()
                .queryForObject(
                        "select stock_lot_id from inventory.stock_lot where location_id = ? and batch_id = ? and condition = ?",
                        UUID.class,
                        locationId,
                        batchId,
                        condition);
    }

    private BigDecimal getLotQty(UUID lotId) {
        return superuserJdbc()
                .queryForObject(
                        "select qty_on_hand from inventory.stock_lot where stock_lot_id = ?", BigDecimal.class, lotId);
    }

    private int countMovements(UUID locationId, UUID batchId) {
        Integer count = superuserJdbc()
                .queryForObject(
                        "select count(*) from inventory.stock_movement where location_id = ? and batch_id = ?",
                        Integer.class,
                        locationId,
                        batchId);
        return count == null ? 0 : count;
    }

    private void plantDrift(UUID lotId, BigDecimal driftedQtyOnHand, UUID entity) {
        ScopeContext scope = own(entity);
        outer.run(
                scope,
                () -> jdbc.update(
                        "update inventory.stock_lot set qty_on_hand = ? where stock_lot_id = ?",
                        driftedQtyOnHand,
                        lotId));
    }

    private static JobExecution execution() {
        return new JobExecution() {
            @Override
            public UUID runId() {
                return UUID.randomUUID();
            }

            @Override
            public void itemsProcessed(int count) {}

            @Override
            public Optional<ScopeContext> systemScope() {
                return Optional.empty();
            }
        };
    }

    private List<KernelRecorder.AuditRecord> audits(String type) {
        return kernel.committedAudit().stream()
                .filter(a -> type.equals(a.eventType()))
                .toList();
    }
}
