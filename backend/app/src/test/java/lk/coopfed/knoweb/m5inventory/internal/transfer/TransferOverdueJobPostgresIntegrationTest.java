package lk.coopfed.knoweb.m5inventory.internal.transfer;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry.ConfigScope;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.internal.control.BusinessDay;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

class TransferOverdueJobPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e67c-0000-7000-8000-000000000002");
    private static final UUID OTHER_ENTITY = UUID.fromString("0190e67c-0000-7000-8000-000000000004");
    private static final String TRANSFER_RECEIPT_DAYS = "inventory.transfer_receipt_days";

    @Autowired
    TransferOverdueJob job;

    @Autowired
    TransferOverdueStore store;

    @Autowired
    TransferOverdueReporter reporter;

    @Autowired
    ConfigRegistry config;

    @Autowired
    OuterCommand outer;

    @Autowired
    BusinessDay businessDay;

    @Value("${coop-erp.business-timezone}")
    String businessZoneId;

    private InventoryFixture fixture;
    private ZoneId businessZone;
    private UUID source;
    private UUID destination;
    private UUID otherSource;
    private UUID otherDestination;
    private boolean overrideSet;

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        fixture.entity(MPCS, "M5OD", "MPCS");
        fixture.entity(OTHER_ENTITY, "M5OE", "MPCS");
        source = fixture.location(MPCS, "WAREHOUSE");
        destination = fixture.location(MPCS, "SHOP");
        otherSource = fixture.location(OTHER_ENTITY, "WAREHOUSE");
        otherDestination = fixture.location(OTHER_ENTITY, "SHOP");
        businessZone = ZoneId.of(businessZoneId);
        kernel.reset();
    }

    @AfterEach
    void clean() {
        if (overrideSet) {
            ScopeContext scope = own(OTHER_ENTITY);
            outer.run(scope, () -> {
                config.set(TRANSFER_RECEIPT_DAYS, ConfigScope.entity(OTHER_ENTITY), "3", scope, "test cleanup");
                return null;
            });
            superuserJdbc()
                    .update(
                            "delete from kernel.config_value where key = ? and scope_entity_id = ?",
                            TRANSFER_RECEIPT_DAYS,
                            OTHER_ENTITY);
        }
        fixture.clean();
    }

    @Test
    void reportsOnConfiguredDayButNotBeforeIt() {
        LocalDate today = businessDay.today();
        UUID due = dispatchedTransfer(
                MPCS,
                source,
                destination,
                today.minusDays(3).atTime(23, 59, 59).atZone(businessZone).toInstant());
        UUID early = dispatchedTransfer(
                MPCS,
                source,
                destination,
                today.minusDays(2).atTime(0, 0, 1).atZone(businessZone).toInstant());

        int reported = job.review(execution());

        assertThat(reported).isEqualTo(1);
        assertThat(overdueAudits()).singleElement().satisfies(a -> {
            assertThat(a.subject().type()).isEqualTo("transfer");
            assertThat(a.subject().id()).isEqualTo(due);
            assertThat(a.scope().entityId()).isEqualTo(MPCS);
            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) a.after();
            assertThat(details)
                    .containsEntry("transferId", due.toString())
                    .containsEntry("fromLocationId", source.toString())
                    .containsEntry("toLocationId", destination.toString())
                    .containsEntry("elapsedDays", 3)
                    .containsEntry("thresholdDays", 3);
        });
        assertThat(overdueAudits()).extracting(a -> a.subject().id()).doesNotContain(early);
    }

    @Test
    void usesEntityOverrideWithoutChangingAnotherEntityDefault() {
        ScopeContext otherScope = own(OTHER_ENTITY);
        outer.run(otherScope, () -> {
            config.set(TRANSFER_RECEIPT_DAYS, ConfigScope.entity(OTHER_ENTITY), "1", otherScope, "test override");
            return null;
        });
        overrideSet = true;

        LocalDate today = businessDay.today();
        Instant yesterday =
                today.minusDays(1).atTime(12, 0).atZone(businessZone).toInstant();
        UUID overridden = dispatchedTransfer(OTHER_ENTITY, otherSource, otherDestination, yesterday);
        UUID defaulted = dispatchedTransfer(MPCS, source, destination, yesterday);
        kernel.reset();

        int reported = job.review(execution());

        assertThat(reported).isEqualTo(1);
        assertThat(overdueAudits()).singleElement().satisfies(a -> {
            assertThat(a.subject().id()).isEqualTo(overridden);
            assertThat(a.scope().entityId()).isEqualTo(OTHER_ENTITY);
            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) a.after();
            assertThat(details).containsEntry("thresholdDays", 1);
        });
        assertThat(overdueAudits()).extracting(a -> a.subject().id()).doesNotContain(defaulted);
        assertThat(outer.run(own(MPCS), () -> config.getInt(TRANSFER_RECEIPT_DAYS, own(MPCS), -1)))
                .isEqualTo(3);
    }

    @Test
    void repeatedRunsDoNotDuplicateAnExistingReview() {
        UUID transfer = dispatchedTransfer(MPCS, source, destination, daysAgo(4));

        assertThat(job.review(execution())).isEqualTo(1);
        assertThat(overdueAudits()).extracting(a -> a.subject().id()).containsExactly(transfer);

        kernel.reset();
        assertThat(job.review(execution())).isZero();
        assertThat(overdueAudits()).isEmpty();
    }

    @Test
    void ownScopeCannotDiscoverAnotherEntitysCandidate() {
        UUID ownTransfer = dispatchedTransfer(MPCS, source, destination, daysAgo(4));
        dispatchedTransfer(OTHER_ENTITY, otherSource, otherDestination, daysAgo(4));

        List<TransferOverdueStore.CandidateTransfer> visible = store.findCandidates(own(MPCS));

        assertThat(visible)
                .extracting(TransferOverdueStore.CandidateTransfer::transferId)
                .containsExactly(ownTransfer);
    }

    @Test
    void receivedAndCancelledTransfersAreIgnoredAndStaleCandidatesAreRevalidated() {
        UUID alreadyReceived = dispatchedTransfer(MPCS, source, destination, daysAgo(4));
        receive(alreadyReceived, MPCS, source, destination);
        assertThat(job.review(execution())).isZero();

        UUID cancelled = dispatchedTransfer(MPCS, source, destination, daysAgo(4));
        cancel(cancelled, MPCS, source, destination);
        assertThat(job.review(execution())).isZero();

        UUID stale = dispatchedTransfer(MPCS, source, destination, daysAgo(4));
        TransferOverdueStore.CandidateTransfer candidate =
                store.findById(stale, own(MPCS)).orElseThrow();
        receive(stale, MPCS, source, destination);
        kernel.reset();

        assertThat(reporter.report(candidate, own(MPCS))).isFalse();
        assertThat(overdueAudits()).isEmpty();
    }

    @Test
    void reviewDoesNotChangeTransferFactsOrStockMovements() {
        UUID transfer = dispatchedTransfer(MPCS, source, destination, daysAgo(3));
        long transfersBefore = count("inventory.transfer");
        long dispatchesBefore = count("inventory.transfer_dispatch");
        long receiptsBefore = count("inventory.transfer_receipt");
        long movementsBefore = count("inventory.stock_movement");

        assertThat(job.review(execution())).isEqualTo(1);

        assertThat(count("inventory.transfer")).isEqualTo(transfersBefore);
        assertThat(count("inventory.transfer_dispatch")).isEqualTo(dispatchesBefore);
        assertThat(count("inventory.transfer_receipt")).isEqualTo(receiptsBefore);
        assertThat(count("inventory.stock_movement")).isEqualTo(movementsBefore);
        assertThat(overdueAudits()).extracting(a -> a.subject().id()).containsExactly(transfer);
    }

    private Instant daysAgo(int days) {
        return businessDay
                .today()
                .minusDays(days)
                .atTime(LocalTime.NOON)
                .atZone(businessZone)
                .toInstant();
    }

    private UUID dispatchedTransfer(UUID entity, UUID fromLocation, UUID toLocation, Instant dispatchedAt) {
        UUID transfer = Ids.next();
        superuserJdbc()
                .update(
                        "insert into inventory.transfer"
                                + " (transfer_id, owner_entity_id, location_id, to_location_id, issued_at)"
                                + " values (?, ?, ?, ?, ?)",
                        transfer,
                        entity,
                        fromLocation,
                        toLocation,
                        Timestamp.from(dispatchedAt.minusSeconds(60)));
        superuserJdbc()
                .update(
                        "insert into inventory.transfer_dispatch"
                                + " (transfer_id, owner_entity_id, location_id, to_location_id, dispatched_at)"
                                + " values (?, ?, ?, ?, ?)",
                        transfer,
                        entity,
                        fromLocation,
                        toLocation,
                        Timestamp.from(dispatchedAt));
        return transfer;
    }

    private void receive(UUID transfer, UUID entity, UUID fromLocation, UUID toLocation) {
        superuserJdbc()
                .update(
                        "insert into inventory.transfer_receipt"
                                + " (transfer_id, owner_entity_id, location_id, from_location_id)"
                                + " values (?, ?, ?, ?)",
                        transfer,
                        entity,
                        toLocation,
                        fromLocation);
    }

    private void cancel(UUID transfer, UUID entity, UUID fromLocation, UUID toLocation) {
        superuserJdbc()
                .update(
                        "insert into inventory.transfer_cancellation"
                                + " (transfer_id, owner_entity_id, location_id, to_location_id, reason,"
                                + " cancelled_from_status, physical_return_verified_by, physical_return_verified_at)"
                                + " values (?, ?, ?, ?, 'Returned', 'IN_TRANSIT', ?, now())",
                        transfer,
                        entity,
                        fromLocation,
                        toLocation,
                        InventoryFixture.USER);
    }

    private long count(String table) {
        Long count = superuserJdbc().queryForObject("select count(*) from " + table, Long.class);
        return count == null ? 0 : count;
    }

    private List<KernelRecorder.AuditRecord> overdueAudits() {
        return kernel.committedAudit().stream()
                .filter(a -> TransferOverdueJob.AUDIT_TRANSFER_OVERDUE.equals(a.eventType()))
                .toList();
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
}
