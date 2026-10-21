package lk.coopfed.knoweb.m5inventory.internal.review;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.at;
import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditReviewReader;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry.ConfigScope;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.AcknowledgeNegativeLot;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.LotNegativeEscalated;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.internal.control.BusinessDay;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests for NegativeLotReviewJob (doc 25 sections 3.2, 10; doc 25A section 7.2; ticket M5-06b / INV-04).
 */
class NegativeLotReviewJobPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e67c-0000-7000-8000-000000000002");
    private static final UUID OTHER_ENTITY = UUID.fromString("0190e67c-0000-7000-8000-000000000003");
    private static final LocalDate EXPIRY = LocalDate.of(2027, 12, 31);
    private static final String NEGATIVE_LOT_ESCALATION_DAYS = "inventory.negative_lot_escalation_days";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    NegativeLotReviewJob job;

    @Autowired
    NegativeLotReviewStore store;

    @Autowired
    NegativeLotEscalationReporter reporter;

    @Autowired
    Handles<AcknowledgeNegativeLot, UUID> acknowledgeHandler;

    @Autowired
    StockLedger ledger;

    @Autowired
    AuditReviewReader auditReviews;

    @Autowired
    ConfigRegistry config;

    @Autowired
    OuterCommand outer;

    @Autowired
    Clock clock;

    @Autowired
    BusinessDay businessDay;

    @Value("${coop-erp.business-timezone}")
    String businessZoneId;

    private ZoneId businessZone;
    private InventoryFixture fixture;
    private UUID shop1;
    private UUID shop2;
    private UUID otherShop;
    private boolean thresholdOverrideSet;

    @BeforeEach
    void arrange() {
        businessZone = ZoneId.of(businessZoneId);
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        fixture.entity(MPCS, "M5NL", "MPCS");
        fixture.entity(OTHER_ENTITY, "M5OT", "MPCS");
        shop1 = fixture.location(MPCS, "SHOP");
        shop2 = fixture.location(MPCS, "SHOP");
        otherShop = fixture.location(OTHER_ENTITY, "SHOP");
        kernel.reset();
    }

    @AfterEach
    void clean() {
        if (thresholdOverrideSet) {
            ScopeContext otherEntityScope = own(OTHER_ENTITY);
            outer.run(otherEntityScope, () -> {
                config.set(
                        NEGATIVE_LOT_ESCALATION_DAYS,
                        ConfigScope.entity(OTHER_ENTITY),
                        "7",
                        otherEntityScope,
                        "test cleanup");
                return null;
            });
            // The supported write invalidates the resolver cache before the test rows are removed.
            superuserJdbc()
                    .update(
                            "delete from kernel.config_value where key = ? and scope_entity_id = ?",
                            NEGATIVE_LOT_ESCALATION_DAYS,
                            OTHER_ENTITY);
        }
        fixture.clean();
    }

    @Test
    void noEscalationBeforeThreshold() {
        UUID sku = fixture.sku(MPCS, "RICE_6DAYS");
        UUID batch = fixture.batch(sku, MPCS, "B-6D", EXPIRY);
        postSale(shop1, batch, "-10", MPCS);

        UUID lotId = getLotId(shop1, batch, "GOOD");
        LocalDate today = businessDay.today();
        // 6 days negative: date is today - 6 days
        Instant sixDaysAgo =
                today.minusDays(6).atTime(14, 0).atZone(businessZone).toInstant();
        setNegativeSince(lotId, sixDaysAgo, MPCS);
        kernel.reset();

        int escalated = job.review(execution());
        assertThat(escalated).isEqualTo(0);
        assertThat(audits(NegativeLotReviewJob.AUDIT_STOCK_LOT_NEGATIVE_ESCALATED))
                .isEmpty();
        assertThat(events(LotNegativeEscalated.class)).isEmpty();
    }

    @Test
    void unacknowledgedNegativeLotEscalatesOnDay7() {
        UUID sku = fixture.sku(MPCS, "RICE_7DAYS");
        UUID batch = fixture.batch(sku, MPCS, "B-7D", EXPIRY);
        postSale(shop1, batch, "-15", MPCS);

        UUID lotId = getLotId(shop1, batch, "GOOD");
        LocalDate today = businessDay.today();
        // Exactly 7 days negative: date is today - 7 days
        Instant sevenDaysAgo =
                today.minusDays(7).atTime(10, 30).atZone(businessZone).toInstant();
        setNegativeSince(lotId, sevenDaysAgo, MPCS);
        kernel.reset();

        int escalated = job.review(execution());
        assertThat(escalated).isEqualTo(1);

        List<KernelRecorder.AuditRecord> auditList = audits(NegativeLotReviewJob.AUDIT_STOCK_LOT_NEGATIVE_ESCALATED);
        assertThat(auditList).singleElement().satisfies(a -> {
            assertThat(a.eventType()).isEqualTo(NegativeLotReviewJob.AUDIT_STOCK_LOT_NEGATIVE_ESCALATED);
            assertThat(a.subject().type()).isEqualTo("stock_lot");
            assertThat(a.subject().id()).isEqualTo(lotId);
            @SuppressWarnings("unchecked")
            Map<String, Object> after = (Map<String, Object>) a.after();
            assertThat(after.get("stockLotId")).isEqualTo(lotId.toString());
            assertThat(after.get("locationId")).isEqualTo(shop1.toString());
            assertThat(after.get("batchId")).isEqualTo(batch.toString());
            assertThat(after.get("condition")).isEqualTo("GOOD");
            assertThat(after.get("qtyOnHand")).isEqualTo("-15.000");
            assertThat(after.get("daysNegative")).isEqualTo(7);
            assertThat(after.get("thresholdDays")).isEqualTo(7);
        });

        assertThat(events(LotNegativeEscalated.class)).singleElement().satisfies((LotNegativeEscalated e) -> {
            assertThat(e.stockLotId()).isEqualTo(lotId);
            assertThat(e.ownerEntityId()).isEqualTo(MPCS);
            assertThat(e.locationId()).isEqualTo(shop1);
            assertThat(e.batchId()).isEqualTo(batch);
            assertThat(e.qtyOnHand()).isEqualByComparingTo("-15.000");
            assertThat(e.daysNegative()).isEqualTo(7);
        });
    }

    @Test
    void negativeTimestampsOnOppositeSidesOfColomboMidnight() {
        UUID sku1 = fixture.sku(MPCS, "SKU_BEFORE_MIDNIGHT");
        UUID batch1 = fixture.batch(sku1, MPCS, "B-MID-1", EXPIRY);
        postSale(shop1, batch1, "-5", MPCS);
        UUID lot1 = getLotId(shop1, batch1, "GOOD");

        UUID sku2 = fixture.sku(MPCS, "SKU_AFTER_MIDNIGHT");
        UUID batch2 = fixture.batch(sku2, MPCS, "B-MID-2", EXPIRY);
        postSale(shop1, batch2, "-8", MPCS);
        UUID lot2 = getLotId(shop1, batch2, "GOOD");

        LocalDate today = businessDay.today();
        // Lot 1: 23:59:59 Colombo on day (today - 7) -> 7 days elapsed -> escalates
        Instant day7Late = today.minusDays(7)
                .atTime(LocalTime.of(23, 59, 59))
                .atZone(businessZone)
                .toInstant();
        setNegativeSince(lot1, day7Late, MPCS);

        // Lot 2: 00:00:01 Colombo on day (today - 6) -> 6 days elapsed -> does not escalate
        Instant day6Early = today.minusDays(6)
                .atTime(LocalTime.of(0, 0, 1))
                .atZone(businessZone)
                .toInstant();
        setNegativeSince(lot2, day6Early, MPCS);
        kernel.reset();

        int escalated = job.review(execution());
        assertThat(escalated).isEqualTo(1);

        assertThat(audits(NegativeLotReviewJob.AUDIT_STOCK_LOT_NEGATIVE_ESCALATED))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.subject().id()).isEqualTo(lot1);
                });
        assertThat(events(LotNegativeEscalated.class)).singleElement().satisfies((LotNegativeEscalated e) -> {
            assertThat(e.stockLotId()).isEqualTo(lot1);
        });
    }

    @Test
    void validAcknowledgementPreventsEscalation() {
        UUID sku = fixture.sku(MPCS, "RICE_ACK");
        UUID batch = fixture.batch(sku, MPCS, "B-ACK", EXPIRY);
        postSale(shop1, batch, "-20", MPCS);
        UUID lotId = getLotId(shop1, batch, "GOOD");

        LocalDate today = businessDay.today();
        Instant eightDaysAgo =
                today.minusDays(8).atTime(10, 0).atZone(businessZone).toInstant();
        setNegativeSince(lotId, eightDaysAgo, MPCS);

        // Acknowledge the negative lot
        acknowledgeHandler.handle(
                new AcknowledgeNegativeLot(lotId, "Oversold by offline cashiers; count pending"), own(MPCS));
        kernel.reset();

        int escalated = job.review(execution());
        assertThat(escalated).isEqualTo(0);
        assertThat(audits(NegativeLotReviewJob.AUDIT_STOCK_LOT_NEGATIVE_ESCALATED))
                .isEmpty();
        assertThat(events(LotNegativeEscalated.class)).isEmpty();
    }

    @Test
    void zeroOrPositiveLotsDoNotEscalate() {
        UUID sku = fixture.sku(MPCS, "RICE_POS");
        UUID batch = fixture.batch(sku, MPCS, "B-POS", EXPIRY);
        postGood(shop1, batch, "50", "120.00", MPCS);
        kernel.reset();

        int escalated = job.review(execution());
        assertThat(escalated).isEqualTo(0);
        assertThat(audits(NegativeLotReviewJob.AUDIT_STOCK_LOT_NEGATIVE_ESCALATED))
                .isEmpty();
    }

    @Test
    void negativeToNegativeUpdatesPreserveOriginalClock() {
        UUID sku = fixture.sku(MPCS, "RICE_MULTI_SALE");
        UUID batch = fixture.batch(sku, MPCS, "B-MS", EXPIRY);
        postSale(shop1, batch, "-5", MPCS);
        UUID lotId = getLotId(shop1, batch, "GOOD");

        LocalDate today = businessDay.today();
        Instant originalNegativeSince =
                today.minusDays(7).atTime(9, 0).atZone(businessZone).toInstant();
        setNegativeSince(lotId, originalNegativeSince, MPCS);

        // Post a second sale: balance becomes -8, but negative_since remains originalNegativeSince (M5-07)
        postSale(shop1, batch, "-3", MPCS);
        LotRow row = getLotRow(lotId);
        assertThat(row.qtyOnHand()).isEqualByComparingTo("-8.000");
        assertThat(row.negativeSince()).isEqualTo(originalNegativeSince);
        kernel.reset();

        int escalated = job.review(execution());
        assertThat(escalated).isEqualTo(1);
        assertThat(audits(NegativeLotReviewJob.AUDIT_STOCK_LOT_NEGATIVE_ESCALATED))
                .hasSize(1);
    }

    @Test
    void recoveryAndLaterRecurrenceBeginNewEscalationPeriod() {
        UUID sku = fixture.sku(MPCS, "RICE_RECOVER");
        UUID batch = fixture.batch(sku, MPCS, "B-REC", EXPIRY);
        postSale(shop1, batch, "-10", MPCS);
        UUID lotId = getLotId(shop1, batch, "GOOD");

        LocalDate today = businessDay.today();
        Instant firstNegativeSince =
                today.minusDays(14).atTime(10, 0).atZone(businessZone).toInstant();
        setNegativeSince(lotId, firstNegativeSince, MPCS);

        // First run: escalates the first period
        int firstEscalated = job.review(execution());
        assertThat(firstEscalated).isEqualTo(1);

        // Recovery: replenish lot to positive balance (+20) -> negative_since is cleared
        postGood(shop1, batch, "30", "100.00", MPCS);
        LotRow recovered = getLotRow(lotId);
        assertThat(recovered.qtyOnHand()).isEqualByComparingTo("20.000");
        assertThat(recovered.negativeSince()).isNull();

        // Later recurrence: oversold again (-25) -> balance is -5, fresh negative_since
        postSale(shop1, batch, "-25", MPCS);
        LotRow recurrent = getLotRow(lotId);
        assertThat(recurrent.qtyOnHand()).isEqualByComparingTo("-5.000");
        assertThat(recurrent.negativeSince()).isNotNull();

        // Second negative period is only 2 days old -> does not escalate
        Instant twoDaysAgo =
                today.minusDays(2).atTime(11, 0).atZone(businessZone).toInstant();
        setNegativeSince(lotId, twoDaysAgo, MPCS);
        kernel.reset();

        int secondRunEarly = job.review(execution());
        assertThat(secondRunEarly).isEqualTo(0);

        // Second negative period reaches 7 days -> escalates second time
        Instant secondPeriodDay7 =
                today.minusDays(7).atTime(11, 0).atZone(businessZone).toInstant();
        setNegativeSince(lotId, secondPeriodDay7, MPCS);
        kernel.reset();

        int secondRunDue = job.review(execution());
        assertThat(secondRunDue).isEqualTo(1);
        assertThat(audits(NegativeLotReviewJob.AUDIT_STOCK_LOT_NEGATIVE_ESCALATED))
                .hasSize(1);
    }

    @Test
    void repeatedExecutionDoesNotDuplicateEscalations() {
        UUID sku = fixture.sku(MPCS, "RICE_DEDUP");
        UUID batch = fixture.batch(sku, MPCS, "B-DEDUP", EXPIRY);
        postSale(shop1, batch, "-12", MPCS);
        UUID lotId = getLotId(shop1, batch, "GOOD");

        LocalDate today = businessDay.today();
        Instant sevenDaysAgo =
                today.minusDays(7).atTime(8, 0).atZone(businessZone).toInstant();
        setNegativeSince(lotId, sevenDaysAgo, MPCS);
        kernel.reset();

        // First run: escalates
        int run1 = job.review(execution());
        assertThat(run1).isEqualTo(1);
        assertThat(audits(NegativeLotReviewJob.AUDIT_STOCK_LOT_NEGATIVE_ESCALATED))
                .hasSize(1);
        assertThat(events(LotNegativeEscalated.class)).hasSize(1);

        // Immediate rerun on same night: deduplicated
        kernel.reset();
        int run2 = job.review(execution());
        assertThat(run2).isEqualTo(0);
        assertThat(audits(NegativeLotReviewJob.AUDIT_STOCK_LOT_NEGATIVE_ESCALATED))
                .isEmpty();
        assertThat(events(LotNegativeEscalated.class)).isEmpty();

        // Rerun next night (lot is now 8 days negative, still unacknowledged): deduplicated
        kernel.reset();
        int run3 = job.review(execution());
        assertThat(run3).isEqualTo(0);
        assertThat(audits(NegativeLotReviewJob.AUDIT_STOCK_LOT_NEGATIVE_ESCALATED))
                .isEmpty();
        assertThat(events(LotNegativeEscalated.class)).isEmpty();
    }

    @Test
    void multipleEntitiesAndLocationsRemainIsolated() {
        UUID sku1 = fixture.sku(MPCS, "SKU_MPCS");
        UUID batch1 = fixture.batch(sku1, MPCS, "B-MPCS", EXPIRY);
        postSale(shop1, batch1, "-10", MPCS);
        UUID lot1 = getLotId(shop1, batch1, "GOOD");

        UUID sku2 = fixture.sku(OTHER_ENTITY, "SKU_OTHER");
        UUID batch2 = fixture.batch(sku2, OTHER_ENTITY, "B-OTHER", EXPIRY);
        postSale(otherShop, batch2, "-15", OTHER_ENTITY);
        UUID lot2 = getLotId(otherShop, batch2, "GOOD");

        LocalDate today = businessDay.today();
        Instant sevenDaysAgo =
                today.minusDays(7).atTime(12, 0).atZone(businessZone).toInstant();
        setNegativeSince(lot1, sevenDaysAgo, MPCS);
        setNegativeSince(lot2, sevenDaysAgo, OTHER_ENTITY);
        kernel.reset();

        int escalated = job.review(execution());
        assertThat(escalated).isEqualTo(2);

        List<KernelRecorder.AuditRecord> auditRecords = audits(NegativeLotReviewJob.AUDIT_STOCK_LOT_NEGATIVE_ESCALATED);
        assertThat(auditRecords).hasSize(2);
        assertThat(auditRecords)
                .extracting(KernelRecorder.AuditRecord::scope)
                .extracting(ScopeContext::entityId)
                .containsExactlyInAnyOrder(MPCS, OTHER_ENTITY);

        List<LotNegativeEscalated> eventList = events(LotNegativeEscalated.class);
        assertThat(eventList).hasSize(2);
        assertThat(eventList)
                .extracting(LotNegativeEscalated::ownerEntityId)
                .containsExactlyInAnyOrder(MPCS, OTHER_ENTITY);
    }

    @Test
    void postgresRlsAndTenantIsolation() {
        UUID sku1 = fixture.sku(MPCS, "SKU_RLS_1");
        UUID batch1 = fixture.batch(sku1, MPCS, "B-RLS-1", EXPIRY);
        postSale(shop1, batch1, "-10", MPCS);
        UUID lot1 = getLotId(shop1, batch1, "GOOD");

        UUID sku2 = fixture.sku(OTHER_ENTITY, "SKU_RLS_2");
        UUID batch2 = fixture.batch(sku2, OTHER_ENTITY, "B-RLS-2", EXPIRY);
        postSale(otherShop, batch2, "-20", OTHER_ENTITY);
        UUID lot2 = getLotId(otherShop, batch2, "GOOD");

        LocalDate today = businessDay.today();
        Instant sevenDaysAgo =
                today.minusDays(7).atTime(12, 0).atZone(businessZone).toInstant();
        setNegativeSince(lot1, sevenDaysAgo, MPCS);
        setNegativeSince(lot2, sevenDaysAgo, OTHER_ENTITY);
        kernel.reset();

        // Review under MPCS's OWN scope for shop1: only lot1 is visible and escalated
        int escalated = job.reviewLocation(shop1, own(MPCS));
        assertThat(escalated).isEqualTo(1);

        assertThat(audits(NegativeLotReviewJob.AUDIT_STOCK_LOT_NEGATIVE_ESCALATED))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.subject().id()).isEqualTo(lot1);
                    assertThat(a.scope().entityId()).isEqualTo(MPCS);
                });

        // Lot2 remains unescalated because OTHER_ENTITY's location was shielded by RLS
        assertThat(auditReviews.reviewExists(
                        NegativeLotReviewJob.AUDIT_STOCK_LOT_NEGATIVE_ESCALATED,
                        lot2,
                        "negativeSince",
                        sevenDaysAgo.toString(),
                        own(OTHER_ENTITY)))
                .isFalse();
    }

    @Test
    void entitySpecificThresholdOverride() {
        UUID overriddenSku = fixture.sku(OTHER_ENTITY, "SKU_CUSTOM_DAYS");
        UUID overriddenBatch = fixture.batch(overriddenSku, OTHER_ENTITY, "B-CUST", EXPIRY);
        postSale(otherShop, overriddenBatch, "-5", OTHER_ENTITY);
        UUID overriddenLotId = getLotId(otherShop, overriddenBatch, "GOOD");

        UUID defaultedSku = fixture.sku(MPCS, "SKU_DEFAULT_DAYS");
        UUID defaultedBatch = fixture.batch(defaultedSku, MPCS, "B-DEFAULT", EXPIRY);
        postSale(shop1, defaultedBatch, "-5", MPCS);
        UUID defaultedLotId = getLotId(shop1, defaultedBatch, "GOOD");

        ScopeContext otherEntityScope = own(OTHER_ENTITY);
        outer.run(otherEntityScope, () -> {
            config.set(
                    NEGATIVE_LOT_ESCALATION_DAYS,
                    ConfigScope.entity(OTHER_ENTITY),
                    "3",
                    otherEntityScope,
                    "test override");
            return null;
        });
        thresholdOverrideSet = true;

        assertThat(outer.run(otherEntityScope, () -> config.getInt(NEGATIVE_LOT_ESCALATION_DAYS, otherEntityScope, -1)))
                .isEqualTo(3);
        assertThat(outer.run(own(MPCS), () -> config.getInt(NEGATIVE_LOT_ESCALATION_DAYS, own(MPCS), -1)))
                .isEqualTo(7);

        LocalDate today = businessDay.today();
        Instant threeDaysAgo =
                today.minusDays(3).atTime(12, 0).atZone(businessZone).toInstant();
        setNegativeSince(overriddenLotId, threeDaysAgo, OTHER_ENTITY);
        setNegativeSince(defaultedLotId, threeDaysAgo, MPCS);
        kernel.reset();

        int escalated = job.review(execution());
        assertThat(escalated).isEqualTo(1);
        assertThat(audits(NegativeLotReviewJob.AUDIT_STOCK_LOT_NEGATIVE_ESCALATED))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.subject().id()).isEqualTo(overriddenLotId);
                    assertThat(a.scope().entityId()).isEqualTo(OTHER_ENTITY);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> after = (Map<String, Object>) a.after();
                    assertThat(after.get("thresholdDays")).isEqualTo(3);
                });
        assertThat(auditReviews.reviewExists(
                        NegativeLotReviewJob.AUDIT_STOCK_LOT_NEGATIVE_ESCALATED,
                        defaultedLotId,
                        "negativeSince",
                        threeDaysAgo.toString(),
                        own(MPCS)))
                .isFalse();
    }

    @Test
    void noChangesToStockBalancesOrMovementHistory() {
        UUID sku = fixture.sku(MPCS, "SKU_IMMUTABLE");
        UUID batch = fixture.batch(sku, MPCS, "B-IMM", EXPIRY);
        postSale(shop1, batch, "-10", MPCS);
        UUID lotId = getLotId(shop1, batch, "GOOD");

        LocalDate today = businessDay.today();
        Instant sevenDaysAgo =
                today.minusDays(7).atTime(12, 0).atZone(businessZone).toInstant();
        setNegativeSince(lotId, sevenDaysAgo, MPCS);

        BigDecimal balanceBefore = getLotRow(lotId).qtyOnHand();
        int movementCountBefore = countMovements(shop1, batch);

        job.review(execution());

        BigDecimal balanceAfter = getLotRow(lotId).qtyOnHand();
        int movementCountAfter = countMovements(shop1, batch);

        assertThat(balanceAfter).isEqualTo(balanceBefore);
        assertThat(movementCountAfter).isEqualTo(movementCountBefore);
    }

    @Test
    void staleCandidateAcknowledgedOrResolvedBeforeReportingDoesNotEscalate() {
        UUID sku = fixture.sku(MPCS, "SKU_STALE");
        UUID batch = fixture.batch(sku, MPCS, "B-STALE", EXPIRY);
        postSale(shop1, batch, "-10", MPCS);
        UUID lotId = getLotId(shop1, batch, "GOOD");

        LocalDate today = businessDay.today();
        Instant sevenDaysAgo =
                today.minusDays(7).atTime(12, 0).atZone(businessZone).toInstant();
        setNegativeSince(lotId, sevenDaysAgo, MPCS);

        NegativeLotReviewStore.CandidateLot candidate =
                store.findCandidateLots(own(MPCS)).get(0);

        // Acknowledge the lot before reporter executes
        acknowledgeHandler.handle(new AcknowledgeNegativeLot(lotId, "Handled just before reporter ran"), own(MPCS));
        kernel.reset();

        // Reporter revalidates under OWN scope and detects acknowledgement -> skips escalation
        boolean reported = reporter.reportEscalation(candidate, 7, own(MPCS));
        assertThat(reported).isFalse();
        assertThat(audits(NegativeLotReviewJob.AUDIT_STOCK_LOT_NEGATIVE_ESCALATED))
                .isEmpty();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void postGood(UUID location, UUID batch, String qty, String cost, UUID entity) {
        ScopeContext scope = own(entity);
        Movement m = new Movement(
                location,
                batch,
                LotCondition.GOOD,
                MovementType.RECEIPT,
                new BigDecimal(qty),
                new BigDecimal(cost),
                null);
        outer.run(scope, () -> ledger.post(new PostMovements(Ids.next(), null, null, List.of(m)), scope));
    }

    private void postSale(UUID location, UUID batch, String qty, UUID entity) {
        ScopeContext scope = at(entity, location);
        Movement m =
                new Movement(location, batch, LotCondition.GOOD, MovementType.SALE, new BigDecimal(qty), null, null);
        outer.run(scope, () -> ledger.post(new PostMovements(Ids.next(), null, null, List.of(m)), scope));
    }

    private UUID getLotId(UUID location, UUID batch, String condition) {
        return superuserJdbc()
                .queryForObject(
                        "select stock_lot_id from inventory.stock_lot where location_id = ? and batch_id = ? and condition = ?",
                        UUID.class,
                        location,
                        batch,
                        condition);
    }

    private record LotRow(BigDecimal qtyOnHand, Instant negativeSince, Instant acknowledgedAt) {}

    private LotRow getLotRow(UUID lotId) {
        return superuserJdbc()
                .queryForObject(
                        "select qty_on_hand, negative_since, negative_acknowledged_at from inventory.stock_lot where stock_lot_id = ?",
                        (rs, n) -> {
                            Timestamp since = rs.getTimestamp("negative_since");
                            Timestamp ack = rs.getTimestamp("negative_acknowledged_at");
                            return new LotRow(
                                    rs.getBigDecimal("qty_on_hand"),
                                    since == null ? null : since.toInstant(),
                                    ack == null ? null : ack.toInstant());
                        },
                        lotId);
    }

    private void setNegativeSince(UUID lotId, Instant negativeSince, UUID entity) {
        outer.run(
                own(entity),
                () -> jdbc.update(
                        "update inventory.stock_lot set negative_since = ? where stock_lot_id = ?",
                        Timestamp.from(negativeSince),
                        lotId));
    }

    private int countMovements(UUID location, UUID batch) {
        Integer count = superuserJdbc()
                .queryForObject(
                        "select count(*) from inventory.stock_movement where location_id = ? and batch_id = ?",
                        Integer.class,
                        location,
                        batch);
        return count == null ? 0 : count;
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
