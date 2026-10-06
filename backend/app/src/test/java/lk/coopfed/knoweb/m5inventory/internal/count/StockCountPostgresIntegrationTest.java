package lk.coopfed.knoweb.m5inventory.internal.count;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.at;
import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.AcknowledgeNegativeLot;
import lk.coopfed.knoweb.m5inventory.api.AdjustmentApproved;
import lk.coopfed.knoweb.m5inventory.api.AdjustmentRejected;
import lk.coopfed.knoweb.m5inventory.api.ApproveAdjustment;
import lk.coopfed.knoweb.m5inventory.api.CountScheduled;
import lk.coopfed.knoweb.m5inventory.api.CountStarted;
import lk.coopfed.knoweb.m5inventory.api.CountSubmitted;
import lk.coopfed.knoweb.m5inventory.api.LotAcknowledged;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.RejectAdjustment;
import lk.coopfed.knoweb.m5inventory.api.ScheduleCount;
import lk.coopfed.knoweb.m5inventory.api.StartCount;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.SubmitCount;
import lk.coopfed.knoweb.m5inventory.query.CountView;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.MovementView;
import lk.coopfed.knoweb.m5inventory.query.NegativeLotView;
import lk.coopfed.knoweb.m5inventory.query.StockControlQueries;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Counts and their adjustment (25A M5-10/M5-11; doc 25 section 3.5, flow 6.3): a count of a
 * location is scheduled, started (the book noted), counted and submitted; variances within
 * tolerance (1 % or 2 units) post at once as COUNT_ADJUST, the others wait for approval by another
 * person. Selling during the count is not a variance (E-06). Every guard with nothing committed.
 */
class StockCountPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e67c-0000-7000-8000-000000000002");

    @Autowired
    ScheduleCountHandler schedule;

    @Autowired
    StartCountHandler start;

    @Autowired
    SubmitCountHandler submit;

    @Autowired
    ApproveAdjustmentHandler approve;

    @Autowired
    RejectAdjustmentHandler reject;

    @Autowired
    AcknowledgeNegativeLotHandler acknowledge;

    @Autowired
    StockControlQueries control;

    @Autowired
    InventoryQueries inventory;

    @Autowired
    StockLedger ledger;

    @Autowired
    OuterCommand outer;

    @Autowired
    lk.coopfed.knoweb.kernel.internal.config.JdbcConfigRegistry config;

    private InventoryFixture fixture;
    private UUID stores;
    private UUID rice;
    private UUID riceBatch;
    private UUID dhal;
    private UUID dhalBatch;
    private UUID approver;

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        fixture.entity(MPCS, "M5CT", "MPCS");
        stores = fixture.location(MPCS, "WAREHOUSE");
        rice = fixture.sku(MPCS, "RICE5");
        riceBatch = fixture.batch(rice, MPCS, "R1", LocalDate.of(2027, 3, 31));
        dhal = fixture.sku(MPCS, "DHAL1");
        dhalBatch = fixture.batch(dhal, MPCS, "D1", LocalDate.of(2027, 5, 31));
        post(MovementType.RECEIPT, riceBatch, "50", "100");
        post(MovementType.RECEIPT, dhalBatch, "10", "300");
        // A grant without a limit approves band 1 (Rs 25,000) since wave 2 (M5-09).
        approver = fixture.userWith(MPCS, "inv.adjust.approve", null);
        kernel.reset();
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void aCountWithinToleranceClosesAndPostsItsVarianceAtOnce() {
        UUID task = schedule.handle(new ScheduleCount(stores, "FULL", List.of(), null), own(MPCS));
        assertThat(control.count(task, own(MPCS)).orElseThrow().status()).isEqualTo("SCHEDULED");

        start.handle(new StartCount(task), at(MPCS, stores));
        CountView counting = control.count(task, own(MPCS)).orElseThrow();
        assertThat(counting.status()).isEqualTo("COUNTING");
        assertThat(counting.expectation())
                .extracting(
                        CountView.Expected::batchId,
                        e -> e.expectedQty().stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(riceBatch, "50"),
                        org.assertj.core.groups.Tuple.tuple(dhalBatch, "10"));

        kernel.reset();
        submit.handle(
                new SubmitCount(task, List.of(counted(riceBatch, "49"), counted(dhalBatch, "10"))), at(MPCS, stores));

        CountView closed = control.count(task, own(MPCS)).orElseThrow();
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.outcome()).isEqualTo("POSTED");
        assertThat(closed.lines()).hasSize(2).allMatch(CountView.Line::withinTolerance);
        assertThat(onHand(riceBatch)).isEqualByComparingTo("49");
        assertThat(inventory.movementsOf(task, own(MPCS))).singleElement().satisfies(m -> {
            assertThat(m.movementType()).isEqualTo("COUNT_ADJUST");
            assertThat(m.qtyDelta()).isEqualByComparingTo("-1");
        });
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("COUNT_SUBMITTED", "STOCK_POSTED", "COUNT_TOLERANCE_CLEARED");
        // wave 2, M5-14: the variance cleared by tolerance is on the exception report.
        assertThat(kernel.committedAudit())
                .filteredOn(a -> a.eventType().equals("COUNT_TOLERANCE_CLEARED"))
                .singleElement()
                .satisfies(a -> assertThat(a.after().toString()).contains(riceBatch.toString(), "-1"));
        assertThat(events(CountSubmitted.class)).singleElement().satisfies(e -> {
            assertThat(e.postedLines()).isEqualTo(1);
            assertThat(e.reviewLines()).isZero();
        });
    }

    // ---- wave 2: the value tolerance, the cap, stock from nothing, the counted moment ----------

    @Test
    void aVarianceWithinTheQuantityToleranceButWorthMoreThanTheValueToleranceWaits() {
        UUID tea = fixture.sku(MPCS, "TEA400");
        UUID teaBatch = fixture.batch(tea, MPCS, "T1", LocalDate.of(2028, 1, 31));
        post(MovementType.RECEIPT, teaBatch, "20", "800");
        UUID task = schedule.handle(new ScheduleCount(stores, "SKUS", List.of(tea), null), own(MPCS));
        start.handle(new StartCount(task), own(MPCS));
        kernel.reset();

        // Two units short: inside the 2-unit tolerance, but Rs 1,600 is above Rs 1,000.
        submit.handle(new SubmitCount(task, List.of(counted(teaBatch, "18"))), own(MPCS));

        CountView review = control.count(task, own(MPCS)).orElseThrow();
        assertThat(review.status()).isEqualTo("VARIANCE_REVIEW");
        assertThat(review.reviewValue()).isEqualByComparingTo("1600.00");
        assertThat(review.lines()).singleElement().satisfies(l -> assertThat(l.withinTolerance())
                .isFalse());
        assertThat(inventory.movementsOf(task, own(MPCS))).isEmpty();
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("COUNT_SUBMITTED");
    }

    @Test
    void aCountWhoseClearedVariancesAddUpToMoreThanTheCapWaitsWhole() {
        superuserJdbc()
                .update(
                        "insert into kernel.config_value (key, scope_entity_id, scope_location_id, value, changed_by)"
                                + " values ('inventory.count_autopost_value_cap', ?, null, '500'::jsonb, ?)",
                        MPCS,
                        InventoryFixture.USER);
        config.invalidate("inventory.count_autopost_value_cap");
        try {
            UUID task = schedule.handle(new ScheduleCount(stores, "FULL", List.of(), null), own(MPCS));
            start.handle(new StartCount(task), own(MPCS));
            kernel.reset();

            // Rice one short (Rs 100) and dhal two short (Rs 600): each within tolerance, Rs 700 together.
            submit.handle(new SubmitCount(task, List.of(counted(riceBatch, "49"), counted(dhalBatch, "8"))), own(MPCS));

            CountView review = control.count(task, own(MPCS)).orElseThrow();
            assertThat(review.status()).isEqualTo("VARIANCE_REVIEW");
            assertThat(review.reviewValue()).isEqualByComparingTo("700.00");
            assertThat(review.lines()).noneMatch(CountView.Line::withinTolerance);
            assertThat(inventory.movementsOf(task, own(MPCS))).isEmpty();
            assertThat(kernel.committedAudit())
                    .extracting(KernelRecorder.AuditRecord::eventType)
                    .doesNotContain("COUNT_TOLERANCE_CLEARED", "STOCK_POSTED");
        } finally {
            superuserJdbc().update("delete from kernel.config_value where key = 'inventory.count_autopost_value_cap'");
            config.invalidate("inventory.count_autopost_value_cap");
        }
    }

    @Test
    void aSurplusOnALotWhoseBookHeldNothingIsAlwaysLookedAt() {
        UUID found = fixture.batch(rice, MPCS, "R2", LocalDate.of(2027, 8, 31));
        UUID task = schedule.handle(new ScheduleCount(stores, "FULL", List.of(), null), own(MPCS));
        start.handle(new StartCount(task), own(MPCS));

        // One bag of a batch the book never held: within the 2-unit tolerance, but stock from nothing.
        submit.handle(
                new SubmitCount(task, List.of(counted(riceBatch, "50"), counted(dhalBatch, "10"), counted(found, "1"))),
                own(MPCS));

        CountView review = control.count(task, own(MPCS)).orElseThrow();
        assertThat(review.status()).isEqualTo("VARIANCE_REVIEW");
        assertThat(review.lines())
                .filteredOn(l -> l.batchId().equals(found))
                .singleElement()
                .satisfies(l -> {
                    assertThat(l.varianceQty()).isEqualByComparingTo("1");
                    assertThat(l.withinTolerance()).isFalse();
                });
        assertThat(inventory.movementsOf(task, own(MPCS))).isEmpty();
    }

    @Test
    void aLineIsMeasuredAgainstTheLotAsItStoodWhenItWasCountedSoALaterSaleIsNoSurplus() {
        UUID task = schedule.handle(new ScheduleCount(stores, "SKUS", List.of(rice), null), own(MPCS));
        start.handle(new StartCount(task), own(MPCS));
        // The shelf holds 50 and is counted now; then the till sells five before the sheet is submitted.
        Instant countedAt = Instant.now();
        post(MovementType.SALE, riceBatch, "-5", null, countedAt.plusSeconds(1));

        submit.handle(
                new SubmitCount(
                        task,
                        List.of(new SubmitCount.Line(
                                riceBatch, LotCondition.GOOD, new BigDecimal("50"), null, countedAt))),
                own(MPCS));

        CountView closed = control.count(task, own(MPCS)).orElseThrow();
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.lines()).singleElement().satisfies(l -> {
            assertThat(l.expectedQty()).isEqualByComparingTo("50");
            assertThat(l.varianceQty()).isEqualByComparingTo("0");
        });
        assertThat(onHand(riceBatch)).isEqualByComparingTo("45");
        assertThat(inventory.movementsOf(task, own(MPCS))).isEmpty();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select counted_at is not null from inventory.count_line where task_id = ?",
                                Boolean.class,
                                task))
                .isTrue();
    }

    @Test
    void scheduleAndStartAreAuditedAndPublished() {
        UUID task = schedule.handle(
                new ScheduleCount(stores, "SKUS", List.of(rice), LocalDate.of(2026, 10, 13)), own(MPCS));
        start.handle(new StartCount(task), own(MPCS));

        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("COUNT_SCHEDULED", "COUNT_STARTED");
        assertThat(events(CountScheduled.class)).singleElement().satisfies(e -> assertThat(e.scopeKind())
                .isEqualTo("SKUS"));
        // By items: only the rice lot is on the sheet.
        assertThat(events(CountStarted.class)).singleElement().satisfies(e -> assertThat(e.lots())
                .isEqualTo(1));
        assertThat(control.count(task, own(MPCS)).orElseThrow().scheduledFor()).isEqualTo(LocalDate.of(2026, 10, 13));
    }

    @Test
    void sellingDuringTheCountIsNotAVarianceAndNothingIsFrozen() {
        UUID task = schedule.handle(new ScheduleCount(stores, "SKUS", List.of(rice), null), own(MPCS));
        start.handle(new StartCount(task), own(MPCS));

        // The till sells five while the shelf is being counted: the ledger takes it, nothing refuses.
        post(MovementType.SALE, riceBatch, "-5", null);
        submit.handle(new SubmitCount(task, List.of(counted(riceBatch, "45"))), own(MPCS));

        CountView closed = control.count(task, own(MPCS)).orElseThrow();
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.lines()).singleElement().satisfies(l -> {
            assertThat(l.expectedQty()).isEqualByComparingTo("45");
            assertThat(l.varianceQty()).isEqualByComparingTo("0");
        });
        assertThat(inventory.movementsOf(task, own(MPCS))).isEmpty();
    }

    @Test
    void aVarianceBeyondToleranceWaitsForAnotherPersonToApproveIt() {
        UUID task = counted("40");

        CountView review = control.count(task, own(MPCS)).orElseThrow();
        assertThat(review.status()).isEqualTo("VARIANCE_REVIEW");
        assertThat(review.reviewValue()).isEqualByComparingTo("1000.00");
        assertThat(review.reviewBand()).isEqualTo(1);
        assertThat(onHand(riceBatch)).isEqualByComparingTo("50");

        kernel.reset();
        assertProblem(
                () -> approve.handle(new ApproveAdjustment(task), own(MPCS)), "m5.adjustment.approver_is_requester");
        assertThat(kernel.committedAudit()).isEmpty();

        approve.handle(new ApproveAdjustment(task), own(MPCS, approver));

        CountView closed = control.count(task, own(MPCS)).orElseThrow();
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.outcome()).isEqualTo("APPROVED");
        assertThat(closed.reviewedBy()).isEqualTo(approver);
        assertThat(onHand(riceBatch)).isEqualByComparingTo("40");
        assertThat(inventory.movementsOf(task, own(MPCS)))
                .extracting(MovementView::qtyDelta)
                .singleElement()
                .satisfies(q -> assertThat(q).isEqualByComparingTo("-10"));
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("ADJUSTMENT_APPROVED", "STOCK_POSTED");
        assertThat(events(AdjustmentApproved.class)).singleElement().satisfies(e -> {
            assertThat(e.approverUserId()).isEqualTo(approver);
            assertThat(e.value()).isEqualByComparingTo("1000.00");
        });
        assertProblem(
                () -> approve.handle(new ApproveAdjustment(task), own(MPCS, approver)), "m5.adjustment.not_in_review");
    }

    @Test
    void aRejectedAdjustmentLeavesTheBookAsItWasAndNeedsAReason() {
        UUID task = counted("40");

        kernel.reset();
        assertProblem(() -> reject.handle(new RejectAdjustment(task, " "), own(MPCS, approver)), "m5.reason_required");
        assertThat(kernel.committedAudit()).isEmpty();

        reject.handle(new RejectAdjustment(task, "Count again, the back shelf was missed"), own(MPCS, approver));

        CountView closed = control.count(task, own(MPCS)).orElseThrow();
        assertThat(closed.outcome()).isEqualTo("REJECTED");
        assertThat(closed.reviewReason()).isEqualTo("Count again, the back shelf was missed");
        assertThat(onHand(riceBatch)).isEqualByComparingTo("50");
        assertThat(kernel.committedAudit()).singleElement().satisfies(a -> {
            assertThat(a.eventType()).isEqualTo("ADJUSTMENT_REJECTED");
            assertThat(a.reason()).isEqualTo("Count again, the back shelf was missed");
        });
        assertThat(events(AdjustmentRejected.class)).hasSize(1);
    }

    @Test
    void everyGuardRefusesWithNothingCommitted() {
        assertProblem(
                () -> schedule.handle(new ScheduleCount(stores, "SKUS", List.of(), null), own(MPCS)),
                "m5.count.scope_invalid");
        assertProblem(
                () -> schedule.handle(new ScheduleCount(stores, "TAGS", List.of(), null), own(MPCS)),
                "m5.count.scope_invalid");
        assertProblem(
                () -> schedule.handle(new ScheduleCount(Ids.next(), "FULL", List.of(), null), own(MPCS)),
                "m5.location.not_in_scope");
        UUID task = schedule.handle(new ScheduleCount(stores, "SKUS", List.of(rice), null), own(MPCS));
        kernel.reset();
        assertProblem(
                () -> schedule.handle(new ScheduleCount(stores, "FULL", List.of(), null), own(MPCS)),
                "m5.count.already_open");
        assertProblem(
                () -> submit.handle(new SubmitCount(task, List.of(counted(riceBatch, "50"))), own(MPCS)),
                "m5.count.not_counting");
        assertProblem(() -> start.handle(new StartCount(Ids.next()), own(MPCS)), "m5.count.not_found");
        assertThat(kernel.committedAudit()).isEmpty();

        start.handle(new StartCount(task), own(MPCS));
        kernel.reset();
        assertProblem(() -> start.handle(new StartCount(task), own(MPCS)), "m5.count.not_scheduled");
        assertProblem(() -> submit.handle(new SubmitCount(task, List.of()), own(MPCS)), "m5.count.line_missing");
        assertProblem(
                () -> submit.handle(
                        new SubmitCount(
                                task,
                                List.of(new SubmitCount.Line(riceBatch, LotCondition.GOOD, BigDecimal.ONE, "damaged"))),
                        own(MPCS)),
                "m5.count.line_invalid");
        assertProblem(
                () -> submit.handle(
                        new SubmitCount(task, List.of(counted(riceBatch, "50"), counted(riceBatch, "49"))), own(MPCS)),
                "m5.count.line_invalid");
        assertProblem(
                () -> submit.handle(new SubmitCount(task, List.of(counted(riceBatch, "-1"))), own(MPCS)),
                "m5.count.line_invalid");
        assertProblem(
                () -> submit.handle(
                        new SubmitCount(task, List.of(counted(riceBatch, "50"), counted(dhalBatch, "10"))), own(MPCS)),
                "m5.count.line_out_of_scope");
        assertProblem(
                () -> submit.handle(
                        new SubmitCount(task, List.of(counted(riceBatch, "50"), counted(Ids.next(), "1"))), own(MPCS)),
                "m5.batch.not_found");
        assertProblem(
                () -> approve.handle(new ApproveAdjustment(task), own(MPCS, approver)), "m5.adjustment.not_in_review");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        // A skipped lot needs only its reason; the count closes with nothing posted.
        submit.handle(
                new SubmitCount(
                        task, List.of(new SubmitCount.Line(riceBatch, LotCondition.GOOD, null, "Shelf blocked"))),
                own(MPCS));
        assertThat(control.count(task, own(MPCS)).orElseThrow().lines())
                .singleElement()
                .satisfies(l -> assertThat(l.skipReason()).isEqualTo("Shelf blocked"));
    }

    @Test
    void aLotBelowZeroIsAcknowledgedOnceWithANote() {
        post(MovementType.SALE, riceBatch, "-60", null);
        NegativeLotView lot =
                control.negativeLots(stores, own(MPCS)).stream().findFirst().orElseThrow();
        assertThat(lot.qtyOnHand()).isEqualByComparingTo("-10");
        assertThat(lot.acknowledgedAt()).isNull();

        kernel.reset();
        assertProblem(
                () -> acknowledge.handle(new AcknowledgeNegativeLot(lot.stockLotId(), ""), own(MPCS)),
                "m5.reason_required");
        acknowledge.handle(
                new AcknowledgeNegativeLot(lot.stockLotId(), "Two tills sold the last bags offline"), own(MPCS));

        assertThat(control.negativeLot(lot.stockLotId(), own(MPCS))
                        .orElseThrow()
                        .acknowledgedAt())
                .isNotNull();
        assertThat(kernel.committedAudit()).singleElement().satisfies(a -> assertThat(a.eventType())
                .isEqualTo("STOCK_LOT_ACKNOWLEDGED"));
        assertThat(events(LotAcknowledged.class)).hasSize(1);
        assertProblem(
                () -> acknowledge.handle(new AcknowledgeNegativeLot(lot.stockLotId(), "again"), own(MPCS)),
                "m5.lot.already_acknowledged");
        UUID dhalLot = inventory.balances(stores, dhal, false, own(MPCS)).get(0).stockLotId();
        assertProblem(
                () -> acknowledge.handle(new AcknowledgeNegativeLot(dhalLot, "fine"), own(MPCS)),
                "m5.lot.not_negative");
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** A count of the rice alone, counted at {@code qty} and submitted. */
    private UUID counted(String qty) {
        UUID task = schedule.handle(new ScheduleCount(stores, "SKUS", List.of(rice), null), own(MPCS));
        start.handle(new StartCount(task), own(MPCS));
        submit.handle(new SubmitCount(task, List.of(counted(riceBatch, qty))), own(MPCS));
        return task;
    }

    private static SubmitCount.Line counted(UUID batch, String qty) {
        return new SubmitCount.Line(batch, LotCondition.GOOD, new BigDecimal(qty), null);
    }

    private void post(MovementType type, UUID batch, String qty, String cost) {
        post(type, batch, qty, cost, null);
    }

    private void post(MovementType type, UUID batch, String qty, String cost, Instant occurredAt) {
        ScopeContext scope = own(MPCS);
        outer.run(
                scope,
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                occurredAt,
                                null,
                                List.of(new Movement(
                                        stores,
                                        batch,
                                        LotCondition.GOOD,
                                        type,
                                        new BigDecimal(qty),
                                        cost == null ? null : new BigDecimal(cost),
                                        null))),
                        scope));
    }

    private BigDecimal onHand(UUID batch) {
        return inventory.balances(stores, null, true, own(MPCS)).stream()
                .filter(l -> l.batchId().equals(batch))
                .findFirst()
                .orElseThrow()
                .qtyOnHand();
    }

    private <T extends DomainEvent> List<T> events(Class<T> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private static void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String id) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                .isEqualTo(id));
    }
}
