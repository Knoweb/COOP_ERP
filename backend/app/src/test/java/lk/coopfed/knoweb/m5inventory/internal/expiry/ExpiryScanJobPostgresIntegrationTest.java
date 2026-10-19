package lk.coopfed.knoweb.m5inventory.internal.expiry;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.at;
import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.CountScheduled;
import lk.coopfed.knoweb.m5inventory.api.ExpiryTaskOpened;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.ScheduleCount;
import lk.coopfed.knoweb.m5inventory.api.StartCount;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.SubmitCount;
import lk.coopfed.knoweb.m5inventory.query.CountView;
import lk.coopfed.knoweb.m5inventory.query.StockControlQueries;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests for ExpiryScanJob and expiry task lifecycle (doc 25 sections 4.6, 6.8; 25A section 7.1).
 */
class ExpiryScanJobPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e67c-0000-7000-8000-000000000002");
    private static final UUID OTHER_ENTITY = UUID.fromString("0190e67c-0000-7000-8000-000000000003");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ExpiryScanJob scanJob;

    @Autowired
    ExpiryTaskStore store;

    @Autowired
    Handles<ScheduleCount, UUID> scheduleCount;

    @Autowired
    Handles<StartCount, Void> startCount;

    @Autowired
    Handles<SubmitCount, Void> submitCount;

    @Autowired
    StockControlQueries controlQueries;

    @Autowired
    StockLedger ledger;

    @Autowired
    OuterCommand outer;

    @Autowired
    Clock clock;

    private InventoryFixture fixture;
    private UUID shop1;
    private UUID shop2;
    private UUID otherShop;
    private LocalDate today;

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        fixture.entity(MPCS, "M5EX", "MPCS");
        fixture.entity(OTHER_ENTITY, "M5OT", "MPCS");
        shop1 = fixture.location(MPCS, "SHOP");
        shop2 = fixture.location(MPCS, "SHOP");
        otherShop = fixture.location(OTHER_ENTITY, "SHOP");
        today = LocalDate.ofInstant(clock.instant(), scanJob.businessZone());
        kernel.reset();
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void batchEnteringWarningWindowCreatesExpiryAndCountTasks() {
        UUID sku = fixture.sku(MPCS, "MILK1");
        UUID batch = fixture.batch(sku, MPCS, "B-EXP-1", today.plusDays(10));
        postGood(shop1, batch, "25", "150.00", MPCS);
        kernel.reset();

        int processed = scanJob.scan(execution());
        assertThat(processed).isGreaterThanOrEqualTo(1);

        List<ExpiryTaskStore.ExpiryTask> active = store.findActiveTasks(shop1);
        assertThat(active).hasSize(1);
        ExpiryTaskStore.ExpiryTask task = active.get(0);
        assertThat(task.batchId()).isEqualTo(batch);
        assertThat(task.skuId()).isEqualTo(sku);
        assertThat(task.daysToExpiry()).isEqualTo(10);
        assertThat(task.qtyAtOpen()).isEqualByComparingTo("25");
        assertThat(task.status()).isEqualTo("OPEN");
        assertThat(task.countTaskId()).isNotNull();

        Optional<CountView> countView = controlQueries.count(task.countTaskId(), own(MPCS));
        assertThat(countView).isPresent();
        assertThat(countView.get().status()).isEqualTo("SCHEDULED");
        assertThat(countView.get().scopeKind()).isEqualTo("SKUS");
        assertThat(countView.get().skuIds()).containsExactly(sku);

        assertThat(audits("EXPIRY_TASK_OPENED")).hasSize(1);
        assertThat(audits("COUNT_SCHEDULED")).hasSize(1);
        assertThat(events(ExpiryTaskOpened.class)).hasSize(1);
        assertThat(events(CountScheduled.class)).hasSize(1);
    }

    @Test
    void batchOutsideWarningWindowCreatesNoTasks() {
        UUID sku = fixture.sku(MPCS, "RICE_LONG");
        UUID batch = fixture.batch(sku, MPCS, "B-FAR-1", today.plusDays(45));
        postGood(shop1, batch, "50", "220.00", MPCS);
        kernel.reset();

        int processed = scanJob.scan(execution());
        assertThat(processed).isEqualTo(0);

        assertThat(store.findActiveTasks(shop1)).isEmpty();
        assertThat(store.findOpenCountTask(shop1)).isEmpty();
        assertThat(audits("EXPIRY_TASK_OPENED")).isEmpty();
        assertThat(events(ExpiryTaskOpened.class)).isEmpty();
    }

    @Test
    void expiryDayBoundaryIsStrictlyHonoured() {
        UUID skuIn = fixture.sku(MPCS, "YOGURT_IN");
        UUID batchIn = fixture.batch(skuIn, MPCS, "B-30D", today.plusDays(30)); // Exactly on window boundary
        postGood(shop1, batchIn, "10", "80.00", MPCS);

        UUID skuOut = fixture.sku(MPCS, "YOGURT_OUT");
        UUID batchOut = fixture.batch(skuOut, MPCS, "B-31D", today.plusDays(31)); // 1 day outside window
        postGood(shop1, batchOut, "10", "80.00", MPCS);
        kernel.reset();

        scanJob.scan(execution());

        List<ExpiryTaskStore.ExpiryTask> active = store.findActiveTasks(shop1);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).batchId()).isEqualTo(batchIn);
    }

    @Test
    void multipleExpiringBatchesAtOneLocationShareOneCountTask() {
        UUID sku1 = fixture.sku(MPCS, "ITEM_A");
        UUID batch1 = fixture.batch(sku1, MPCS, "B-A", today.plusDays(5));
        postGood(shop1, batch1, "12", "100.00", MPCS);

        UUID sku2 = fixture.sku(MPCS, "ITEM_B");
        UUID batch2 = fixture.batch(sku2, MPCS, "B-B", today.plusDays(15));
        postGood(shop1, batch2, "8", "200.00", MPCS);
        kernel.reset();

        int processed = scanJob.scan(execution());
        assertThat(processed).isEqualTo(2);

        List<ExpiryTaskStore.ExpiryTask> tasks = store.findActiveTasks(shop1);
        assertThat(tasks).hasSize(2);
        UUID sharedCountTaskId = tasks.get(0).countTaskId();
        assertThat(sharedCountTaskId).isNotNull();
        assertThat(tasks.get(1).countTaskId()).isEqualTo(sharedCountTaskId);

        CountView count = controlQueries.count(sharedCountTaskId, own(MPCS)).orElseThrow();
        assertThat(count.skuIds()).containsExactlyInAnyOrder(sku1, sku2);
        assertThat(audits("COUNT_SCHEDULED")).hasSize(1);
        assertThat(events(CountScheduled.class)).hasSize(1);
        assertThat(events(ExpiryTaskOpened.class)).hasSize(2);
    }

    @Test
    void twoLocationsHoldingSameBatchAreProcessedIndependently() {
        UUID sku = fixture.sku(MPCS, "CHEESE");
        UUID batch = fixture.batch(sku, MPCS, "B-CH", today.plusDays(7));
        postGood(shop1, batch, "10", "500.00", MPCS);
        postGood(shop2, batch, "15", "500.00", MPCS);
        kernel.reset();

        scanJob.scan(execution());

        List<ExpiryTaskStore.ExpiryTask> tasksShop1 = store.findActiveTasks(shop1);
        List<ExpiryTaskStore.ExpiryTask> tasksShop2 = store.findActiveTasks(shop2);

        assertThat(tasksShop1).hasSize(1);
        assertThat(tasksShop2).hasSize(1);
        assertThat(tasksShop1.get(0).countTaskId())
                .isNotEqualTo(tasksShop2.get(0).countTaskId());
    }

    @Test
    void zeroStockAndDamagedLotsAreExcluded() {
        UUID skuZero = fixture.sku(MPCS, "ZERO_ITEM");
        UUID batchZero = fixture.batch(skuZero, MPCS, "B-ZERO", today.plusDays(5));
        // Lot with zero balance
        postGood(shop1, batchZero, "5", "100.00", MPCS);
        postOut(shop1, batchZero, "5", MPCS);

        UUID skuDamaged = fixture.sku(MPCS, "DAMAGED_ITEM");
        UUID batchDamaged = fixture.batch(skuDamaged, MPCS, "B-DMG", today.plusDays(5));
        postDamaged(shop1, batchDamaged, "4", "100.00", MPCS);
        kernel.reset();

        int processed = scanJob.scan(execution());
        assertThat(processed).isEqualTo(0);
        assertThat(store.findActiveTasks(shop1)).isEmpty();
    }

    @Test
    void duplicateScanExecutionIsIdempotent() {
        UUID sku = fixture.sku(MPCS, "BREAD");
        UUID batch = fixture.batch(sku, MPCS, "B-BRD", today.plusDays(3));
        postGood(shop1, batch, "20", "120.00", MPCS);
        kernel.reset();

        int firstRun = scanJob.scan(execution());
        assertThat(firstRun).isEqualTo(1);
        int auditCountFirst = kernel.committedAudit().size();
        int eventCountFirst = kernel.committedEvents().size();

        int secondRun = scanJob.scan(execution());
        assertThat(secondRun).isEqualTo(0);
        assertThat(kernel.committedAudit().size()).isEqualTo(auditCountFirst);
        assertThat(kernel.committedEvents().size()).isEqualTo(eventCountFirst);
        assertThat(store.findActiveTasks(shop1)).hasSize(1);
    }

    @Test
    void existingFullCountTaskIsReusedWithoutDuplicateCount() {
        UUID openCountId = scheduleCount.handle(new ScheduleCount(shop1, "FULL", List.of(), null), own(MPCS));
        kernel.reset();

        UUID sku = fixture.sku(MPCS, "BUTTER");
        UUID batch = fixture.batch(sku, MPCS, "B-BTR", today.plusDays(12));
        postGood(shop1, batch, "6", "400.00", MPCS);

        scanJob.scan(execution());

        List<ExpiryTaskStore.ExpiryTask> tasks = store.findActiveTasks(shop1);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).countTaskId()).isEqualTo(openCountId);
        // Did not create a second count task
        assertThat(audits("COUNT_SCHEDULED")).isEmpty();
    }

    @Test
    void incompatibleOpenCountDefersSchedulingUntilClosed() {
        UUID otherSku = fixture.sku(MPCS, "TEA");
        UUID otherBatch = fixture.batch(otherSku, MPCS, "B-TEA", today.plusDays(100));
        postGood(shop1, otherBatch, "10", "300.00", MPCS);

        // Schedule an existing count task for TEA only
        UUID existingCountId =
                scheduleCount.handle(new ScheduleCount(shop1, "SKUS", List.of(otherSku), null), own(MPCS));
        kernel.reset();

        // Expiring batch is SUGAR (not covered by the open TEA count)
        UUID expiringSku = fixture.sku(MPCS, "SUGAR");
        UUID expiringBatch = fixture.batch(expiringSku, MPCS, "B-SGR", today.plusDays(5));
        postGood(shop1, expiringBatch, "30", "200.00", MPCS);

        // 1. First scan: open expiry task is recorded safely, but count scheduling is deferred
        scanJob.scan(execution());

        List<ExpiryTaskStore.ExpiryTask> pending = store.findPendingUnlinkedTasks(shop1);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).countTaskId()).isNull();
        assertThat(pending.get(0).status()).isEqualTo("OPEN");

        // 2. Complete and close the existing count task
        startCount.handle(new StartCount(existingCountId), at(MPCS, shop1));
        submitCount.handle(
                new SubmitCount(
                        existingCountId,
                        List.of(new SubmitCount.Line(otherBatch, LotCondition.GOOD, new BigDecimal("10"), null))),
                at(MPCS, shop1));
        assertThat(controlQueries
                        .count(existingCountId, own(MPCS))
                        .orElseThrow()
                        .status())
                .isEqualTo("CLOSED");
        kernel.reset();

        // 3. Second scan: now that shop1 has no open count, the pending expiry task is scheduled!
        scanJob.scan(execution());

        List<ExpiryTaskStore.ExpiryTask> active = store.findActiveTasks(shop1);
        assertThat(active).hasSize(1);
        UUID newlyScheduledCount = active.get(0).countTaskId();
        assertThat(newlyScheduledCount).isNotNull();
        assertThat(newlyScheduledCount).isNotEqualTo(existingCountId);

        CountView view = controlQueries.count(newlyScheduledCount, own(MPCS)).orElseThrow();
        assertThat(view.status()).isEqualTo("SCHEDULED");
        assertThat(view.skuIds()).contains(expiringSku);
    }

    @Test
    void countSubmissionTransitionsLinkedExpiryTaskToCounted() {
        UUID sku = fixture.sku(MPCS, "EGGS");
        UUID batch = fixture.batch(sku, MPCS, "B-EGG", today.plusDays(4));
        postGood(shop1, batch, "100", "30.00", MPCS);
        kernel.reset();

        scanJob.scan(execution());

        ExpiryTaskStore.ExpiryTask task = store.findActiveTasks(shop1).get(0);
        assertThat(task.status()).isEqualTo("OPEN");
        UUID countTaskId = task.countTaskId();

        // Start count
        startCount.handle(new StartCount(countTaskId), at(MPCS, shop1));

        // Submit count with counted quantity equal to book
        submitCount.handle(
                new SubmitCount(
                        countTaskId,
                        List.of(new SubmitCount.Line(batch, LotCondition.GOOD, new BigDecimal("100"), null))),
                at(MPCS, shop1));

        // Expiry task transitioned to COUNTED
        ExpiryTaskStore.ExpiryTask updated = store.findById(task.taskId()).orElseThrow();
        assertThat(updated.status()).isEqualTo("COUNTED");
    }

    @Test
    void lotDepletionClosesExpiryTask() {
        UUID sku = fixture.sku(MPCS, "FISH");
        UUID batch = fixture.batch(sku, MPCS, "B-FSH", today.plusDays(2));
        postGood(shop1, batch, "10", "400.00", MPCS);
        kernel.reset();

        scanJob.scan(execution());
        ExpiryTaskStore.ExpiryTask task = store.findActiveTasks(shop1).get(0);
        assertThat(task.status()).isEqualTo("OPEN");

        // Deplete the stock to 0
        postOut(shop1, batch, "10", MPCS);
        kernel.reset();

        // Next scan run closes the exhausted expiry task
        scanJob.scan(execution());

        ExpiryTaskStore.ExpiryTask closed = store.findById(task.taskId()).orElseThrow();
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.closedAt()).isNotNull();

        assertThat(audits("EXPIRY_TASK_CLOSED")).hasSize(1);
    }

    @Test
    void multiTenantIsolationIsPreserved() {
        UUID skuOther = fixture.sku(OTHER_ENTITY, "OTHER_MILK");
        UUID batchOther = fixture.batch(skuOther, OTHER_ENTITY, "B-OTH", today.plusDays(5));
        postGood(otherShop, batchOther, "15", "180.00", OTHER_ENTITY);
        kernel.reset();

        scanJob.scan(execution());

        List<ExpiryTaskStore.ExpiryTask> otherTasks = store.findActiveTasks(otherShop);
        assertThat(otherTasks).hasSize(1);
        assertThat(otherTasks.get(0).ownerEntityId()).isEqualTo(OTHER_ENTITY);

        // Entity MPCS sees nothing of OTHER_ENTITY
        assertThat(store.findActiveTasks(shop1)).isEmpty();
    }

    @Test
    void alreadyExpiredStockDoesNotCreateSellingCountTasks() {
        UUID skuExpired = fixture.sku(MPCS, "OLD_CURD");
        UUID batchExpired = fixture.batch(skuExpired, MPCS, "B-OLD", today.minusDays(2)); // Expired 2 days ago
        postGood(shop1, batchExpired, "8", "120.00", MPCS);
        kernel.reset();

        scanJob.scan(execution());

        // Does not create a count task for selling expired goods
        assertThat(store.findActiveTasks(shop1)).isEmpty();
        assertThat(store.findOpenCountTask(shop1)).isEmpty();
    }

    @Test
    void deferredTaskCrossingExpiryDateIsScheduledWhenCountCloses() {
        UUID blockerSku = fixture.sku(MPCS, "BLOCKER_TEA");
        UUID blockerBatch = fixture.batch(blockerSku, MPCS, "B-BLK", today.plusDays(100));
        postGood(shop1, blockerBatch, "10", "300.00", MPCS);

        // Schedule an existing count task for BLOCKER_TEA only
        UUID blockerCountId =
                scheduleCount.handle(new ScheduleCount(shop1, "SKUS", List.of(blockerSku), null), own(MPCS));
        kernel.reset();

        // Expiring batch is YOGURT which enters window (expires in 2 days)
        UUID expiringSku = fixture.sku(MPCS, "YOGURT");
        UUID expiringBatch = fixture.batch(expiringSku, MPCS, "B-YGT", today.plusDays(2));
        postGood(shop1, expiringBatch, "25", "50.00", MPCS);

        // First scan: expiry task is recorded in OPEN status, but count scheduling is deferred
        scanJob.scan(execution());
        List<ExpiryTaskStore.ExpiryTask> pending = store.findPendingUnlinkedTasks(shop1);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).countTaskId()).isNull();
        assertThat(pending.get(0).status()).isEqualTo("OPEN");

        // The date moves forward 5 days: batch expiry date is now in the past (already expired!)
        superuserJdbc()
                .update(
                        "update inventory.stock_lot set expiry_date = ? where location_id = ? and batch_id = ?",
                        today.minusDays(1),
                        shop1,
                        expiringBatch);

        // Now blocker count task is started and submitted (closed)
        startCount.handle(new StartCount(blockerCountId), at(MPCS, shop1));
        submitCount.handle(
                new SubmitCount(
                        blockerCountId,
                        List.of(new SubmitCount.Line(blockerBatch, LotCondition.GOOD, new BigDecimal("10"), null))),
                at(MPCS, shop1));
        assertThat(controlQueries.count(blockerCountId, own(MPCS)).orElseThrow().status())
                .isEqualTo("CLOSED");
        kernel.reset();

        // Second scan: Even though the batch has crossed its expiry date and is now expired,
        // the deferred expiry task is rediscovered and scheduled into a count task!
        scanJob.scan(execution());

        List<ExpiryTaskStore.ExpiryTask> active = store.findActiveTasks(shop1);
        assertThat(active).hasSize(1);
        UUID newlyScheduledCount = active.get(0).countTaskId();
        assertThat(newlyScheduledCount).isNotNull();
        assertThat(newlyScheduledCount).isNotEqualTo(blockerCountId);

        CountView view = controlQueries.count(newlyScheduledCount, own(MPCS)).orElseThrow();
        assertThat(view.status()).isEqualTo("SCHEDULED");
        assertThat(view.skuIds()).contains(expiringSku);
        // Expiry task remains OPEN (not prematurely closed) because positive stock remains
        assertThat(active.get(0).status()).isEqualTo("OPEN");
    }

    @Test
    void expiredStockWithPositiveOnHandRemainsOpenUntilDepleted() {
        UUID sku = fixture.sku(MPCS, "PANEER");
        UUID batch = fixture.batch(sku, MPCS, "B-PNR", today.plusDays(1));
        postGood(shop1, batch, "15", "250.00", MPCS);
        kernel.reset();

        // 1. Initial scan opens task
        scanJob.scan(execution());
        ExpiryTaskStore.ExpiryTask task = store.findActiveTasks(shop1).get(0);
        assertThat(task.status()).isEqualTo("OPEN");

        // 2. Date passes expiry date while positive stock (15) remains
        superuserJdbc()
                .update(
                        "update inventory.stock_lot set expiry_date = ? where location_id = ? and batch_id = ?",
                        today.minusDays(2),
                        shop1,
                        batch);

        // Run scan job again
        scanJob.scan(execution());

        // The task MUST NOT be closed simply because expiry date passed while positive stock remains
        ExpiryTaskStore.ExpiryTask stillOpen = store.findById(task.taskId()).orElseThrow();
        assertThat(stillOpen.status()).isEqualTo("OPEN");

        // 3. Stock is depleted / written off to 0
        postOut(shop1, batch, "15", MPCS);
        kernel.reset();

        // Run scan job again: now that on-hand is 0, the task transitions to CLOSED
        scanJob.scan(execution());

        ExpiryTaskStore.ExpiryTask closed = store.findById(task.taskId()).orElseThrow();
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.closedAt()).isNotNull();
    }

    @Test
    void multipleBatchesAndMixedSkusConsolidatedIntoSingleCountTask() {
        UUID sku1 = fixture.sku(MPCS, "CHEESE");
        UUID sku2 = fixture.sku(MPCS, "BUTTER_SALTED");
        UUID batch1a = fixture.batch(sku1, MPCS, "B-CHS-A", today.plusDays(4));
        UUID batch1b = fixture.batch(sku1, MPCS, "B-CHS-B", today.plusDays(6));
        UUID batch2 = fixture.batch(sku2, MPCS, "B-BTR-S", today.plusDays(3));

        postGood(shop1, batch1a, "10", "500.00", MPCS);
        postGood(shop1, batch1b, "20", "520.00", MPCS);
        postGood(shop1, batch2, "30", "350.00", MPCS);
        kernel.reset();

        scanJob.scan(execution());

        List<ExpiryTaskStore.ExpiryTask> tasks = store.findActiveTasks(shop1);
        assertThat(tasks).hasSize(3);

        UUID countTaskId = tasks.get(0).countTaskId();
        assertThat(countTaskId).isNotNull();
        // All 3 batches share the exact same count task ID
        assertThat(tasks.get(1).countTaskId()).isEqualTo(countTaskId);
        assertThat(tasks.get(2).countTaskId()).isEqualTo(countTaskId);

        // Exactly one count task was scheduled
        assertThat(audits("COUNT_SCHEDULED")).hasSize(1);
        CountView countView = controlQueries.count(countTaskId, own(MPCS)).orElseThrow();
        assertThat(countView.scopeKind()).isEqualTo("SKUS");
        assertThat(countView.skuIds()).containsExactlyInAnyOrder(sku1, sku2);
    }

    @Test
    void databaseRlsEnforcesEntityIsolation() {
        UUID sku1 = fixture.sku(MPCS, "LOCAL_RICE");
        UUID batch1 = fixture.batch(sku1, MPCS, "B-RCE", today.plusDays(5));
        postGood(shop1, batch1, "50", "150.00", MPCS);

        scanJob.scan(execution());

        // Under app_rw with OWN scope of OTHER_ENTITY: sees 0 rows
        List<UUID> rowsForOther = outer.run(
                own(OTHER_ENTITY),
                () -> jdbc.query(
                        "select task_id from inventory.expiry_task", (rs, n) -> rs.getObject("task_id", UUID.class)));
        assertThat(rowsForOther).isEmpty();

        // Under app_rw with OWN scope of MPCS: sees the task
        List<UUID> rowsForMpcs = outer.run(
                own(MPCS),
                () -> jdbc.query(
                        "select task_id from inventory.expiry_task", (rs, n) -> rs.getObject("task_id", UUID.class)));
        assertThat(rowsForMpcs).hasSize(1);
    }

    @Test
    void idempotentRepeatedRunsEmitNoDuplicateAuditOrDomainEvents() {
        UUID sku = fixture.sku(MPCS, "APPLES");
        UUID batch = fixture.batch(sku, MPCS, "B-APL", today.plusDays(7));
        postGood(shop1, batch, "40", "80.00", MPCS);
        kernel.reset();

        // Run 1: opens task and schedules count
        int run1 = scanJob.scan(execution());
        assertThat(run1).isEqualTo(1);
        assertThat(audits("EXPIRY_TASK_OPENED")).hasSize(1);
        assertThat(audits("COUNT_SCHEDULED")).hasSize(1);
        assertThat(events(ExpiryTaskOpened.class)).hasSize(1);
        assertThat(events(CountScheduled.class)).hasSize(1);
        kernel.reset();

        // Run 2: idempotent, no duplicate task, no duplicate count, no duplicate events
        int run2 = scanJob.scan(execution());
        assertThat(run2).isEqualTo(0);
        assertThat(audits("EXPIRY_TASK_OPENED")).isEmpty();
        assertThat(audits("COUNT_SCHEDULED")).isEmpty();
        assertThat(events(ExpiryTaskOpened.class)).isEmpty();
        assertThat(events(CountScheduled.class)).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Test Helpers
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

    private void postOut(UUID location, UUID batch, String qty, UUID entity) {
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
                                        MovementType.WRITE_OFF,
                                        new BigDecimal(qty).negate(),
                                        new BigDecimal("100.00"),
                                        null))),
                        scope));
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

    private <T extends DomainEvent> List<T> events(Class<T> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }
}
