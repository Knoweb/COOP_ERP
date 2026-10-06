package lk.coopfed.knoweb.m5inventory.internal.ledger;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.at;
import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
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

    // ---- crossings within one posting (wave 2, M5-07) ------------------------------------------

    @Test
    void aLotNegativeBeforeAndAfterOnePostingKeepsItsNegativePeriodAndRecordsNoCrossing() {
        post(own(MPCS), Ids.next(), move(shop, batch, LotCondition.GOOD, MovementType.RECEIPT, "1", "50"));
        List<PostedMovement> sale =
                post(at(MPCS, shop), Ids.next(), move(shop, batch, LotCondition.GOOD, MovementType.SALE, "-4", null));
        UUID lot = sale.get(0).stockLotId();
        Object since = negativeSince(lot);
        assertThat(since).isNotNull();
        kernel.reset();

        // -3, then +5 and -4 in one posting: the lot never stopped being negative in a committed state.
        post(
                own(MPCS),
                Ids.next(),
                move(shop, batch, LotCondition.GOOD, MovementType.RECEIPT, "5", "50"),
                move(shop, batch, LotCondition.GOOD, MovementType.WRITE_OFF, "-4", null));

        assertThat(negativeSince(lot)).isEqualTo(since);
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("STOCK_POSTED");
        assertThat(events(LotNegative.class)).isEmpty();
    }

    @Test
    void aLotNegativeOnlyBeforeOnePostingClearsOnceAndOneNegativeOnlyAfterGoesNegativeOnce() {
        post(own(MPCS), Ids.next(), move(shop, batch, LotCondition.GOOD, MovementType.RECEIPT, "1", "50"));
        List<PostedMovement> sale =
                post(at(MPCS, shop), Ids.next(), move(shop, batch, LotCondition.GOOD, MovementType.SALE, "-4", null));
        UUID lot = sale.get(0).stockLotId();
        kernel.reset();

        // -3, then -1 and +6: cleared, once.
        post(
                own(MPCS),
                Ids.next(),
                move(shop, batch, LotCondition.GOOD, MovementType.WRITE_OFF, "-1", null),
                move(shop, batch, LotCondition.GOOD, MovementType.RECEIPT, "6", "50"));
        assertThat(negativeSince(lot)).isNull();
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("STOCK_POSTED", "STOCK_LOT_NEGATIVE_CLEARED");
        assertThat(events(LotNegative.class)).isEmpty();

        kernel.reset();
        // 2, then -5 and +1: negative, once, at its final quantity.
        post(
                own(MPCS),
                Ids.next(),
                move(shop, batch, LotCondition.GOOD, MovementType.WRITE_OFF, "-5", null),
                move(shop, batch, LotCondition.GOOD, MovementType.RECEIPT, "1", "50"));
        assertThat(negativeSince(lot)).isNotNull();
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("STOCK_POSTED", "STOCK_LOT_NEGATIVE");
        assertThat(events(LotNegative.class)).singleElement().satisfies(e -> assertThat(e.qtyOnHand())
                .isEqualByComparingTo("-2"));
    }

    // ---- the property: balance equals the sum of movements under random interleavings -------

    @Test
    void lotBalancesAndTheEntityAverageEqualTheLedgerUnderConcurrentRandomPostings() throws Exception {
        int workers = 4;
        int postingsEach = 20;
        UUID packs = fixture.sku(MPCS, "RICE1KG");
        UUID packBatch = fixture.batch(packs, MPCS, "P2410", LocalDate.of(2027, 3, 31));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                long seed = 1000L + w;
                Callable<Integer> worker = () -> {
                    Random random = new Random(seed);
                    Deque<BigDecimal[]> inTransit = new ArrayDeque<>();
                    Deque<BigDecimal[]> repacked = new ArrayDeque<>();
                    int failures = 0;
                    for (int p = 0; p < postingsEach; p++) {
                        try {
                            randomPosting(random, packBatch, inTransit, repacked);
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
        // A lot is flagged negative exactly while it is below zero (M5-07).
        assertThat(db.queryForList(
                        "select stock_lot_id from inventory.stock_lot"
                                + " where (negative_since is null) <> (qty_on_hand >= 0)",
                        UUID.class))
                .as("lots whose negative flag disagrees with their sign")
                .isEmpty();
        // The movements in the order the postings committed. Every posting starts at the
        // warehouse, and a posting holds the warehouse's sequence row until it commits, so the
        // warehouse numbers order the postings; inside one, the warehouse's movements come first
        // and the shop's after, each in its own sequence.
        List<Map<String, Object>> ledgerRows = db.queryForList(
                "select document_id, location_id, sku_id, movement_type, qty_delta, unit_cost_at_movement,"
                        + " movement_seq from inventory.stock_movement");
        Map<Object, Long> postingOrder = new HashMap<>();
        for (Map<String, Object> m : ledgerRows) {
            if (warehouse.equals(m.get("location_id"))) {
                postingOrder.merge(m.get("document_id"), (Long) m.get("movement_seq"), Math::min);
            }
        }
        ledgerRows.sort(Comparator.<Map<String, Object>>comparingLong(m -> postingOrder.get(m.get("document_id")))
                .thenComparing(m -> warehouse.equals(m.get("location_id")) ? 0 : 1)
                .thenComparingLong(m -> (Long) m.get("movement_seq")));
        for (UUID theSku : List.of(sku, packs)) {
            // The entity's quantity is the sum of all its movements of the SKU.
            BigDecimal total = db.queryForObject(
                    "select sum(qty_delta) from inventory.stock_movement where sku_id = ?", BigDecimal.class, theSku);
            CostRow stored = costRow(MPCS, theSku);
            assertThat(stored.qtyOnHand()).isEqualByComparingTo(total);
            // The average equals the one recomputed from scratch from each movement's own
            // recorded cost, replayed in commit order: an out movement replayed "at its recorded
            // cost" is the same rule whether it left at the average or at a given cost, so the
            // recorded costs alone decide the average, across the transfer pairs and the repacks.
            CostRow replayed = CostRow.empty();
            for (Map<String, Object> m : ledgerRows) {
                if (theSku.equals(m.get("sku_id"))) {
                    replayed = CostService.apply(
                            replayed,
                            MovementType.valueOf((String) m.get("movement_type")),
                            (BigDecimal) m.get("qty_delta"),
                            (BigDecimal) m.get("unit_cost_at_movement"));
                }
            }
            assertThat(stored.avgCost()).as("the average of %s", theSku).isEqualByComparingTo(replayed.avgCost());
            assertThat(stored.avgCost().signum()).isGreaterThanOrEqualTo(0);
        }
        // The sequence is dense at each location: 1..n with no gap and no repeat.
        for (UUID location : List.of(warehouse, shop)) {
            List<Long> seqs = db.queryForList(
                    "select movement_seq from inventory.stock_movement where location_id = ? order by 1",
                    Long.class,
                    location);
            for (int i = 0; i < seqs.size(); i++) {
                assertThat(seqs.get(i)).isEqualTo(i + 1L);
            }
        }
        assertThat(db.queryForObject(
                        "select count(*) from inventory.stock_movement where movement_type = 'TRANSFER_IN'",
                        Integer.class))
                .as("the run received transfers")
                .isPositive();
    }

    /**
     * One random posting, always starting at the warehouse: plain movements there; a transfer
     * out to the shop; the shop receiving one of this worker's transfers at the cost it left
     * with; a repack of the rice into packs; or the reversal of one of this worker's repacks,
     * the packs going out at the repack's own cost.
     */
    private void randomPosting(
            Random random, UUID packBatch, Deque<BigDecimal[]> inTransit, Deque<BigDecimal[]> repacked) {
        String qty = BigDecimal.valueOf(1 + random.nextInt(20_000), 3).toPlainString();
        int kind = random.nextInt(8);
        if (kind == 0) {
            List<PostedMovement> out = post(
                    own(MPCS),
                    Ids.next(),
                    move(warehouse, batch, LotCondition.GOOD, MovementType.TRANSFER_OUT, "-" + qty, null));
            inTransit.add(new BigDecimal[] {new BigDecimal(qty), out.get(0).unitCostAtMovement()});
            return;
        }
        if (kind == 1 && !inTransit.isEmpty()) {
            BigDecimal[] transfer = inTransit.poll();
            post(
                    own(MPCS),
                    Ids.next(),
                    randomMovement(random),
                    new Movement(
                            shop, batch, LotCondition.GOOD, MovementType.TRANSFER_IN, transfer[0], transfer[1], null));
            return;
        }
        if (kind == 2) {
            String packsMade = BigDecimal.valueOf(1 + random.nextInt(20_000), 3).toPlainString();
            String cost = BigDecimal.valueOf(1 + random.nextInt(2_000_000), 4).toPlainString();
            post(
                    own(MPCS),
                    Ids.next(),
                    move(warehouse, batch, LotCondition.GOOD, MovementType.REPACK_CONSUME, "-" + qty, null),
                    move(warehouse, packBatch, LotCondition.GOOD, MovementType.REPACK_PRODUCE, packsMade, cost));
            repacked.add(new BigDecimal[] {new BigDecimal(qty), new BigDecimal(packsMade), new BigDecimal(cost)});
            return;
        }
        if (kind == 3 && !repacked.isEmpty()) {
            BigDecimal[] repack = repacked.poll();
            post(
                    own(MPCS),
                    Ids.next(),
                    new Movement(
                            warehouse,
                            packBatch,
                            LotCondition.GOOD,
                            MovementType.REPACK_CONSUME,
                            repack[1].negate(),
                            repack[2],
                            null),
                    new Movement(
                            warehouse,
                            batch,
                            LotCondition.GOOD,
                            MovementType.REPACK_PRODUCE,
                            repack[0],
                            BigDecimal.valueOf(1 + random.nextInt(2_000_000), 4),
                            null));
            return;
        }
        List<Movement> movements = new ArrayList<>();
        int size = 1 + random.nextInt(3);
        for (int m = 0; m < size; m++) {
            movements.add(randomMovement(random));
        }
        post(own(MPCS), Ids.next(), movements.toArray(Movement[]::new));
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

    private Object negativeSince(UUID lot) {
        return superuserJdbc()
                .queryForObject(
                        "select negative_since from inventory.stock_lot where stock_lot_id = ?", Object.class, lot);
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
