package lk.coopfed.knoweb.m5inventory.internal.ledger;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.at;
import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.LotNegative;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.PostedMovement;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.StockMoved;
import lk.coopfed.knoweb.m5inventory.internal.ledger.CostService.CostRow;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The ledger (25A sections 6.1 and 9): what a posting writes, audits and publishes; the entity
 * average; negative lots; the guards that need the database; row-level security on a shop's
 * posting; and the property of AGENTS.md, "balance equals the sum of movements under random
 * interleavings", with concurrent postings.
 */
class LedgerServicePostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e675-0000-7000-8000-000000000002");
    private static final UUID OTHER = UUID.fromString("0190e675-0000-7000-8000-000000000003");

    @Autowired
    StockLedger ledger;

    /** The ledger is an internal command: it runs inside another command, as M5's handlers call it. */
    @Autowired
    OuterCommand outer;

    private InventoryFixture fixture;
    private UUID warehouse;
    private UUID shop;
    private UUID sku;
    private UUID batch;
    private UUID batch2;

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        warehouse = fixture.location(MPCS, "WAREHOUSE");
        shop = fixture.location(MPCS, "SHOP");
        sku = fixture.sku(MPCS, "MILK400");
        batch = fixture.batch(sku, MPCS, "B2411A", LocalDate.of(2027, 3, 31));
        batch2 = fixture.batch(sku, MPCS, "B2503C", LocalDate.of(2027, 6, 30));
        kernel.reset();
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void aReceiptCreatesTheLotPostsTheMovementAveragesTheCostAuditsAndPublishes() {
        UUID document = Ids.next();
        List<PostedMovement> posted =
                post(own(MPCS), document, move(warehouse, batch, LotCondition.GOOD, MovementType.RECEIPT, "40", "100"));

        assertThat(posted).singleElement().satisfies(p -> {
            assertThat(p.movementSeq()).isEqualTo(1L);
            assertThat(p.source()).isEqualTo("central");
            assertThat(p.unitCostAtMovement()).isEqualByComparingTo("100");
            assertThat(p.lotQtyOnHand()).isEqualByComparingTo("40");
        });
        Map<String, Object> lot = superuserJdbc()
                .queryForMap(
                        "select * from inventory.stock_lot where stock_lot_id = ?",
                        posted.get(0).stockLotId());
        assertThat(lot.get("owner_entity_id")).isEqualTo(MPCS);
        assertThat(lot.get("sku_id")).isEqualTo(sku);
        assertThat((BigDecimal) lot.get("qty_on_hand")).isEqualByComparingTo("40");
        assertThat((BigDecimal) lot.get("unit_cost")).isEqualByComparingTo("100");
        assertThat(lot.get("last_movement_seq")).isEqualTo(1L);
        assertThat(costRow(MPCS, sku)).isEqualTo(new CostRow(new BigDecimal("40.000"), new BigDecimal("100.0000")));
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from inventory.stock_movement where document_id = ?",
                                Integer.class,
                                document))
                .isEqualTo(1);

        assertThat(kernel.committedAudit()).singleElement().satisfies(a -> {
            assertThat(a.eventType()).isEqualTo("STOCK_POSTED");
            assertThat(a.subject().id()).isEqualTo(document);
        });
        assertThat(events(StockMoved.class)).singleElement().satisfies(e -> {
            assertThat(e.movementType()).isEqualTo("RECEIPT");
            assertThat(e.skuId()).isEqualTo(sku);
            assertThat(e.qtyDelta()).isEqualByComparingTo("40");
            assertThat(e.documentId()).isEqualTo(document);
            assertThat(e.ownerEntityId()).isEqualTo(MPCS);
        });
    }

    @Test
    void anIssueCarriesTheEntityAverageAndASecondReceiptReaverages() {
        post(own(MPCS), Ids.next(), move(warehouse, batch, LotCondition.GOOD, MovementType.RECEIPT, "10", "100"));
        post(own(MPCS), Ids.next(), move(shop, batch2, LotCondition.GOOD, MovementType.RECEIPT, "30", "120"));
        assertThat(costRow(MPCS, sku).avgCost()).isEqualByComparingTo("115");

        List<PostedMovement> sale =
                post(at(MPCS, shop), Ids.next(), move(shop, batch2, LotCondition.GOOD, MovementType.SALE, "-4", null));

        assertThat(sale.get(0).unitCostAtMovement()).isEqualByComparingTo("115");
        assertThat(sale.get(0).lotQtyOnHand()).isEqualByComparingTo("26");
        assertThat(costRow(MPCS, sku)).isEqualTo(new CostRow(new BigDecimal("36.000"), new BigDecimal("115.0000")));
    }

    @Test
    void anOversellTakesTheLotNegativeFlagsItAndTheNextReceiptClearsIt() {
        post(own(MPCS), Ids.next(), move(shop, batch, LotCondition.GOOD, MovementType.RECEIPT, "2", "50"));
        kernel.reset();

        UUID receipt = Ids.next();
        List<PostedMovement> sale =
                post(at(MPCS, shop), receipt, move(shop, batch, LotCondition.GOOD, MovementType.SALE, "-5", null));

        assertThat(sale.get(0).lotQtyOnHand()).isEqualByComparingTo("-3");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select negative_since is not null from inventory.stock_lot where stock_lot_id = ?",
                                Boolean.class,
                                sale.get(0).stockLotId()))
                .isTrue();
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("STOCK_POSTED", "STOCK_LOT_NEGATIVE");
        assertThat(events(LotNegative.class)).singleElement().satisfies(e -> {
            assertThat(e.qtyOnHand()).isEqualByComparingTo("-3");
            assertThat(e.documentId()).isEqualTo(receipt);
        });

        kernel.reset();
        post(own(MPCS), Ids.next(), move(shop, batch, LotCondition.GOOD, MovementType.RECEIPT, "10", "60"));

        assertThat(superuserJdbc()
                        .queryForObject(
                                "select negative_since is null from inventory.stock_lot where stock_lot_id = ?",
                                Boolean.class,
                                sale.get(0).stockLotId()))
                .isTrue();
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("STOCK_POSTED", "STOCK_LOT_NEGATIVE_CLEARED");
        assertThat(events(LotNegative.class)).isEmpty();
    }

    @Test
    void aSaleWithoutALotCreatesItAtZeroCostedAtTheAverage() {
        post(own(MPCS), Ids.next(), move(warehouse, batch, LotCondition.GOOD, MovementType.RECEIPT, "10", "80"));

        List<PostedMovement> sale =
                post(at(MPCS, shop), Ids.next(), move(shop, batch, LotCondition.GOOD, MovementType.SALE, "-1", null));

        assertThat(sale.get(0).lotQtyOnHand()).isEqualByComparingTo("-1");
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select unit_cost from inventory.stock_lot where stock_lot_id = ?",
                                BigDecimal.class,
                                sale.get(0).stockLotId()))
                .isEqualByComparingTo("80");
    }

    @Test
    void movementNumbersAreDensePerLocationAndSource() {
        post(
                own(MPCS),
                Ids.next(),
                move(warehouse, batch, LotCondition.GOOD, MovementType.RECEIPT, "5", "10"),
                move(warehouse, batch, LotCondition.DAMAGED, MovementType.RECEIPT, "1", "10"),
                move(shop, batch, LotCondition.GOOD, MovementType.RECEIPT, "5", "10"));
        post(own(MPCS), Ids.next(), move(warehouse, batch2, LotCondition.GOOD, MovementType.RECEIPT, "5", "10"));

        assertThat(superuserJdbc()
                        .queryForList(
                                "select movement_seq from inventory.stock_movement where location_id = ? order by movement_seq",
                                Long.class,
                                warehouse))
                .containsExactly(1L, 2L, 3L);
        assertThat(superuserJdbc()
                        .queryForList(
                                "select movement_seq from inventory.stock_movement where location_id = ?",
                                Long.class,
                                shop))
                .containsExactly(1L);
    }

    // ---- guards that need the database: nothing is committed --------------------------------

    @Test
    void aLocationOfAnotherEntityIsRefusedAndNothingIsCommitted() {
        UUID elsewhere = fixture.location(OTHER, "SHOP");

        assertProblem(
                () -> post(
                        own(MPCS),
                        Ids.next(),
                        move(elsewhere, batch, LotCondition.GOOD, MovementType.RECEIPT, "1", "1")),
                "m5.location.not_in_scope");
        assertNothingCommitted();
    }

    @Test
    void anUnknownBatchIsRefusedAndNothingIsCommitted() {
        assertProblem(
                () -> post(
                        own(MPCS),
                        Ids.next(),
                        move(warehouse, Ids.next(), LotCondition.GOOD, MovementType.RECEIPT, "1", "1")),
                "m5.batch.not_found");
        assertNothingCommitted();
    }

    @Test
    void aShopSessionPostsAtItsOwnLocationOnly() {
        // The warehouse is the entity's, but not the shop session's: M1 does not show it to a
        // shop-scoped session, so the guard refuses it before row-level security would.
        assertProblem(
                () -> post(
                        at(MPCS, shop),
                        Ids.next(),
                        move(warehouse, batch, LotCondition.GOOD, MovementType.RECEIPT, "1", "1")),
                "m5.location.not_in_scope");
        assertNothingCommitted();
    }

    @Test
    void theLedgerRunsOnlyInsideAnotherCommand() {
        assertThatThrownBy(() -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(move(warehouse, batch, LotCondition.GOOD, MovementType.RECEIPT, "1", "1"))),
                        own(MPCS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("internal command");
    }

    // ---- the property: balance equals the sum of movements under random interleavings -------

    @Test
    void lotBalancesAndTheEntityAverageEqualTheLedgerUnderConcurrentRandomPostings() throws Exception {
        int workers = 4;
        int postingsEach = 15;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                long seed = 1000L + w;
                Callable<Integer> worker = () -> {
                    Random random = new Random(seed);
                    int failures = 0;
                    for (int p = 0; p < postingsEach; p++) {
                        List<Movement> movements = new ArrayList<>();
                        int size = 1 + random.nextInt(3);
                        for (int m = 0; m < size; m++) {
                            movements.add(randomMovement(random));
                        }
                        try {
                            post(own(MPCS), Ids.next(), movements.toArray(Movement[]::new));
                        } catch (RuntimeException deadlockOrSerialization) {
                            failures++;
                        }
                    }
                    return failures;
                };
                results.add(pool.submit(worker));
            }
            int failed = 0;
            for (Future<Integer> result : results) {
                failed += result.get();
            }
            assertThat(failed)
                    .as("postings that failed (a deadlock would show here)")
                    .isZero();
        } finally {
            pool.shutdownNow();
        }

        JdbcTemplate db = superuserJdbc();
        // Every lot: qty_on_hand is the sum of its movements.
        assertThat(db.queryForList(
                        """
                        select l.stock_lot_id
                          from inventory.stock_lot l
                          left join (select location_id, batch_id, condition, sum(qty_delta) as total
                                       from inventory.stock_movement group by 1, 2, 3) m
                            on m.location_id = l.location_id and m.batch_id = l.batch_id and m.condition = l.condition
                         where l.qty_on_hand <> coalesce(m.total, 0)
                        """,
                        UUID.class))
                .as("lots whose balance is not the sum of their movements")
                .isEmpty();
        // The entity's quantity is the sum of all its movements of the SKU.
        BigDecimal total = db.queryForObject(
                "select sum(qty_delta) from inventory.stock_movement where sku_id = ?", BigDecimal.class, sku);
        CostRow stored = costRow(MPCS, sku);
        assertThat(stored.qtyOnHand()).isEqualByComparingTo(total);
        // The average equals the one recomputed from scratch, replaying the ledger in the order
        // the postings committed: one location, one source, so the dense sequence is that order.
        CostRow replayed = CostRow.empty();
        for (Map<String, Object> m : db.queryForList(
                "select movement_type, qty_delta, unit_cost_at_movement from inventory.stock_movement"
                        + " where sku_id = ? order by movement_seq",
                sku)) {
            replayed = CostService.apply(
                    replayed,
                    MovementType.valueOf((String) m.get("movement_type")),
                    (BigDecimal) m.get("qty_delta"),
                    (BigDecimal) m.get("unit_cost_at_movement"));
        }
        assertThat(stored.avgCost()).isEqualByComparingTo(replayed.avgCost());
        assertThat(stored.avgCost().signum()).isGreaterThanOrEqualTo(0);
        // The sequence is dense: 1..n with no gap and no repeat.
        List<Long> seqs = db.queryForList(
                "select movement_seq from inventory.stock_movement where location_id = ? order by 1",
                Long.class,
                warehouse);
        for (int i = 0; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).isEqualTo(i + 1L);
        }
    }

    private Movement randomMovement(Random random) {
        UUID theBatch = random.nextBoolean() ? batch : batch2;
        LotCondition condition = random.nextInt(4) == 0 ? LotCondition.DAMAGED : LotCondition.GOOD;
        String qty = BigDecimal.valueOf(1 + random.nextInt(20_000), 3).toPlainString();
        return switch (random.nextInt(5)) {
            case 0, 1 ->
                move(
                        warehouse,
                        theBatch,
                        condition,
                        MovementType.RECEIPT,
                        qty,
                        BigDecimal.valueOf(1 + random.nextInt(2_000_000), 4).toPlainString());
            case 2 -> move(warehouse, theBatch, condition, MovementType.SALE, "-" + qty, null);
            case 3 -> move(warehouse, theBatch, condition, MovementType.WRITE_OFF, "-" + qty, null);
            default ->
                move(
                        warehouse,
                        theBatch,
                        condition,
                        MovementType.COUNT_ADJUST,
                        (random.nextBoolean() ? "" : "-") + qty,
                        null);
        };
    }

    // ---- helpers --------------------------------------------------------------------------

    private List<PostedMovement> post(ScopeContext scope, UUID document, Movement... movements) {
        return outer.run(scope, () -> ledger.post(new PostMovements(document, null, null, List.of(movements)), scope));
    }

    private static Movement move(
            UUID location, UUID batch, LotCondition condition, MovementType type, String qty, String cost) {
        return new Movement(
                location,
                batch,
                condition,
                type,
                new BigDecimal(qty),
                cost == null ? null : new BigDecimal(cost),
                null);
    }

    private CostRow costRow(UUID entity, UUID sku) {
        return superuserJdbc()
                .queryForObject(
                        "select qty_on_hand, avg_cost from inventory.entity_sku_cost where owner_entity_id = ? and sku_id = ?",
                        (rs, n) -> new CostRow(rs.getBigDecimal(1), rs.getBigDecimal(2)),
                        entity,
                        sku);
    }

    private <T extends DomainEvent> List<T> events(Class<T> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private void assertNothingCommitted() {
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
        assertThat(superuserJdbc().queryForObject("select count(*) from inventory.stock_movement", Integer.class))
                .isZero();
        assertThat(superuserJdbc().queryForObject("select count(*) from inventory.stock_lot", Integer.class))
                .isZero();
    }

    private static void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String id) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                .isEqualTo(id));
    }
}
