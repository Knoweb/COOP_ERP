package lk.coopfed.knoweb.m5inventory.internal.consumers;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.at;
import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.OverridePick;
import lk.coopfed.knoweb.m5inventory.api.PickOverridden;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.PickListView;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
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

/** PostgreSQL proof of the insert-only manual FEFO exception in FR-INV-040. */
class OverridePickPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID OWNER = TEST_FEDERATION;
    private static final UUID OTHER = UUID.fromString("0190e678-0000-7000-8000-000000000003");

    @Autowired
    Handles<ReserveDelivery, UUID> reserve;

    @Autowired
    Handles<DispatchDelivery, Integer> dispatch;

    @Autowired
    Handles<OverridePick, UUID> override;

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
    private UUID early;
    private UUID late;

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        warehouse = fixture.location(OWNER, "WAREHOUSE");
        otherWarehouse = fixture.location(OWNER, "WAREHOUSE");
        sku = fixture.sku(OWNER, "MILK");
        otherSku = fixture.sku(OWNER, "SOAP");
        early = fixture.batch(sku, OWNER, "EARLY", LocalDate.of(2027, 3, 31));
        late = fixture.batch(sku, OWNER, "LATE", LocalDate.of(2027, 9, 30));
        post(warehouse, early, "10", "900");
        post(warehouse, late, "10", "950");
        kernel.reset();
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void overridePreservesTheSuggestionReservesTheReplacementAndDispatchesItOnceAtTheEntityCost() {
        Pick pick = openPick("4");
        UUID lateLot = lot(warehouse, late);

        UUID overrideId = runOverride(pick, lateLot, "Front pallet is inaccessible");

        assertThat(superuserJdbc()
                        .queryForObject(
                                "select stock_lot_id from inventory.pick_list_line where pick_line_id = ?",
                                UUID.class,
                                pick.pickLineId()))
                .as("the FEFO suggestion is immutable")
                .isEqualTo(pick.originalLotId());
        assertThat(superuserJdbc()
                        .queryForMap(
                                "select original_stock_lot_id, replacement_stock_lot_id, qty, reason"
                                        + " from inventory.pick_override where override_id = ?",
                                overrideId))
                .containsEntry("original_stock_lot_id", pick.originalLotId())
                .containsEntry("replacement_stock_lot_id", lateLot)
                .containsEntry("reason", "Front pallet is inaccessible");
        assertThat(queries.pickList(pick.deliveryNoteId(), own(OWNER))
                        .orElseThrow()
                        .lines())
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.pickLineId()).isEqualTo(pick.pickLineId());
                    assertThat(line.stockLotId()).isEqualTo(lateLot);
                    assertThat(line.batchId()).isEqualTo(late);
                });
        assertThat(queries.availability(List.of(warehouse), List.of(sku), own(OWNER)))
                .singleElement()
                .satisfies(a -> assertThat(a.available()).isEqualByComparingTo("16"));
        assertThat(kernel.committedAudit()).singleElement().satisfies(record -> {
            assertThat(record.eventType()).isEqualTo("PICK_OVERRIDDEN");
            assertThat(record.subject()).isEqualTo(Subject.of("pick_override", overrideId));
            assertThat(record.before()).isEqualTo(Map.of("stockLotId", pick.originalLotId()));
            assertThat(record.after()).isInstanceOf(Map.class);
            Map<?, ?> after = (Map<?, ?>) record.after();
            assertThat(after.get("pickListId")).isEqualTo(pick.pickListId());
            assertThat(after.get("pickLineId")).isEqualTo(pick.pickLineId());
            assertThat(after.get("stockLotId")).isEqualTo(lateLot);
            assertThat((BigDecimal) after.get("qty")).isEqualByComparingTo("4");
            assertThat(record.scope().userId()).isEqualTo(InventoryFixture.USER);
            assertThat(record.reason()).isEqualTo("Front pallet is inaccessible");
        });
        assertThat(events(PickOverridden.class)).singleElement().satisfies(event -> {
            assertThat(event.originalStockLotId()).isEqualTo(pick.originalLotId());
            assertThat(event.replacementStockLotId()).isEqualTo(lateLot);
            assertThat(event.qty()).isEqualByComparingTo("4");
            assertThat(event.reason()).isEqualTo("Front pallet is inaccessible");
        });

        kernel.reset();
        dispatch.handle(new DispatchDelivery(pick.deliveryNoteId(), OWNER, null), own(OWNER));

        assertThat(queries.movementsOf(pick.deliveryNoteId(), own(OWNER)))
                .singleElement()
                .satisfies(movement -> {
                    assertThat(movement.batchId()).isEqualTo(late);
                    assertThat(movement.qtyDelta()).isEqualByComparingTo("-4");
                    assertThat(movement.unitCostAtMovement()).isEqualByComparingTo("925.0000");
                });
        assertThat(onHand(early)).isEqualByComparingTo("10");
        assertThat(onHand(late)).isEqualByComparingTo("6");

        dispatch.handle(new DispatchDelivery(pick.deliveryNoteId(), OWNER, null), own(OWNER));
        assertThat(queries.movementsOf(pick.deliveryNoteId(), own(OWNER))).hasSize(1);
    }

    @Test
    void replacementMustBeVisibleGoodInDateAvailableAndOfTheSameSkuAndLocation() {
        Pick pick = openPick("4");
        UUID wrongSkuBatch = fixture.batch(otherSku, OWNER, "OTHER-SKU", LocalDate.of(2027, 8, 1));
        UUID wrongSkuLot = post(warehouse, wrongSkuBatch, "10", "20");
        UUID wrongLocationLot = post(otherWarehouse, late, "10", "950");
        UUID expiredBatch = fixture.batch(sku, OWNER, "EXPIRED", LocalDate.of(2025, 1, 1));
        UUID expiredLot = post(warehouse, expiredBatch, "10", "800");
        kernel.reset();

        assertProblem(() -> runOverride(pick, lot(warehouse, late), "   "), "m5.pick.reason_invalid");
        assertProblem(
                () -> runOverride(pick, pick.originalLotId(), "The suggested lot"),
                "m5.pick.replacement_same_as_original");
        assertProblem(() -> runOverride(pick, Ids.next(), "A missing lot"), "m5.pick.replacement_lot_not_found");
        assertProblem(() -> runOverride(pick, wrongSkuLot, "Wrong item"), "m5.pick.sku_mismatch");
        assertProblem(() -> runOverride(pick, wrongLocationLot, "Wrong warehouse"), "m5.pick.location_mismatch");
        assertProblem(() -> runOverride(pick, expiredLot, "Expired"), "m5.pick.lot_expired");
        assertProblem(
                () -> override.handle(
                        new OverridePick(pick.deliveryNoteId(), pick.pickLineId(), lot(warehouse, late), "Other"),
                        own(OTHER)),
                "m5.pick_list.not_found");
        assertThat(overrides()).isZero();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void replacementWithInsufficientFreeStockIsRefusedAtomically() {
        Pick pick = openPick("8");
        UUID lateLot = lot(warehouse, late);
        UUID anotherDelivery = Ids.next();
        reserve.handle(
                new ReserveDelivery(
                        anotherDelivery,
                        OWNER,
                        List.of(new ReserveDelivery.Line(Ids.next(), sku, late, new BigDecimal("5")))),
                own(OWNER));
        kernel.reset();

        assertProblem(() -> runOverride(pick, lateLot, "Need the later batch"), "m5.pick.lot_unavailable");
        assertThat(overrides()).isZero();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void commandRequiresOwnUserAndALineOfTheNamedDeliveryNote() {
        Pick first = openPick("1");
        Pick second = openPick("1");
        UUID lateLot = lot(warehouse, late);

        ScopeContext view = federationView(OWNER);
        assertProblem(
                () -> override.handle(
                        new OverridePick(first.deliveryNoteId(), first.pickLineId(), lateLot, "Read-only viewer"),
                        view),
                "m5.scope.own_required");
        ScopeContext system = InventoryFixture.system(OWNER);
        assertProblem(
                () -> override.handle(
                        new OverridePick(first.deliveryNoteId(), first.pickLineId(), lateLot, "System actor"), system),
                "m5.pick.user_required");
        assertProblem(
                () -> runOverride(
                        first.deliveryNoteId(), second.pickLineId(), lateLot, "Line from another delivery", own(OWNER)),
                "m5.pick.line_not_found");
        assertProblem(
                () -> runOverride(first.deliveryNoteId(), Ids.next(), lateLot, "Unknown line", own(OWNER)),
                "m5.pick.line_not_found");

        assertThat(overrides()).isZero();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void shortLinesAndDamagedOrEmptyReplacementLotsAreRefused() {
        UUID damagedBatch = fixture.batch(sku, OWNER, "DAMAGED", LocalDate.of(2027, 8, 1));
        UUID damagedLot = post(warehouse, damagedBatch, "2", "800", LotCondition.DAMAGED);
        UUID emptyBatch = fixture.batch(sku, OWNER, "EMPTY", LocalDate.of(2027, 8, 1));
        UUID emptyLot = post(warehouse, emptyBatch, "1", "800");
        move(warehouse, emptyBatch, LotCondition.GOOD, MovementType.SALE, "-1", null);

        Pick pick = openPick("1");
        assertProblem(() -> runOverride(pick, damagedLot, "Damaged stock"), "m5.pick.lot_unavailable");
        assertProblem(() -> runOverride(pick, emptyLot, "Empty stock"), "m5.pick.lot_unavailable");

        UUID delivery = Ids.next();
        UUID pickList = reserve.handle(
                new ReserveDelivery(
                        delivery,
                        OWNER,
                        List.of(new ReserveDelivery.Line(Ids.next(), sku, null, new BigDecimal("30")))),
                own(OWNER));
        UUID shortLine = superuserJdbc()
                .queryForObject(
                        "select pick_line_id from inventory.pick_list_line"
                                + " where pick_list_id = ? and stock_lot_id is null",
                        UUID.class,
                        pickList);
        kernel.reset();

        assertProblem(
                () -> runOverride(delivery, shortLine, lot(warehouse, late), "No original lot", own(OWNER)),
                "m5.pick.short_line_not_overridable");
        assertThat(overrides()).isZero();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void warehouseLocationScopeMayOverrideItsOwnPick() {
        Pick pick = openPick("2");
        UUID lateLot = lot(warehouse, late);

        UUID overrideId = runOverride(
                pick.deliveryNoteId(),
                pick.pickLineId(),
                lateLot,
                "Warehouse-scoped replacement",
                at(OWNER, warehouse));

        assertThat(overrideId).isNotNull();
        assertThat(overrides()).isEqualTo(1);
        assertThat(kernel.committedAudit()).singleElement().satisfies(record -> {
            assertThat(record.scope().locationId()).isEqualTo(warehouse);
            assertThat(record.reason()).isEqualTo("Warehouse-scoped replacement");
        });
        assertThat(events(PickOverridden.class)).hasSize(1);
    }

    @Test
    void dispatchedAndAlreadyOverriddenLinesCannotBeChangedAgain() {
        Pick first = openPick("2");
        UUID lateLot = lot(warehouse, late);
        runOverride(first, lateLot, "First and only override");
        kernel.reset();

        assertProblem(() -> runOverride(first, lateLot, "Duplicate"), "m5.pick.already_overridden");

        Pick second = openPick("2");
        dispatch.handle(new DispatchDelivery(second.deliveryNoteId(), OWNER, null), own(OWNER));
        kernel.reset();
        assertProblem(() -> runOverride(second, lateLot, "Too late"), "m5.pick_list.not_open");
        assertThat(overrides()).isEqualTo(1);
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void eventFailureRollsBackTheOverrideAndReleasesTheOriginalReservationOnlyOnCommit() {
        Pick pick = openPick("4");
        UUID lateLot = lot(warehouse, late);
        kernel.reset();
        kernel.failNextPublishWith(new IllegalStateException("outbox unavailable"));

        assertThatThrownBy(() -> runOverride(pick, lateLot, "Will roll back"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox unavailable");

        assertThat(overrides()).isZero();
        assertThat(queries.pickList(pick.deliveryNoteId(), own(OWNER))
                        .orElseThrow()
                        .lines())
                .singleElement()
                .extracting(PickListView.Pick::stockLotId)
                .isEqualTo(pick.originalLotId());
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void concurrentOverrideAndDispatchProduceOneConsistentOutboundMovement() throws Exception {
        Pick pick = openPick("4");
        UUID lateLot = lot(warehouse, late);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> overrideResult =
                    pool.submit(() -> outcome(start, () -> runOverride(pick, lateLot, "Concurrent replacement")));
            Future<String> dispatchResult = pool.submit(() -> outcome(
                    start,
                    () -> dispatch.handle(new DispatchDelivery(pick.deliveryNoteId(), OWNER, null), own(OWNER))));
            start.countDown();

            assertThat(List.of(overrideResult.get(20, TimeUnit.SECONDS), dispatchResult.get(20, TimeUnit.SECONDS)))
                    .contains("OK");
        } finally {
            pool.shutdownNow();
        }

        assertThat(queries.movementsOf(pick.deliveryNoteId(), own(OWNER)))
                .singleElement()
                .satisfies(movement -> {
                    assertThat(movement.qtyDelta()).isEqualByComparingTo("-4");
                    if (overrides() == 1) {
                        assertThat(movement.batchId()).isEqualTo(late);
                    } else {
                        assertThat(movement.batchId()).isEqualTo(early);
                    }
                });
    }

    @Test
    void aLocationScopeCannotOverrideAnotherWarehousesPick() {
        Pick pick = openPick("4");

        assertProblem(
                () -> override.handle(
                        new OverridePick(
                                pick.deliveryNoteId(), pick.pickLineId(), lot(warehouse, late), "Sibling warehouse"),
                        at(OWNER, otherWarehouse)),
                "m5.pick.line_not_found");
        assertThat(overrides()).isZero();
    }

    @Test
    void theHttpCommandValidatesItsShapeAndReplaysOneIdempotentOverride() {
        Pick pick = openPick("4");
        UUID lateLot = lot(warehouse, late);
        String url = "/v1/inventory/pick-lists/" + pick.deliveryNoteId() + "/lines/" + pick.pickLineId() + "/override";

        HttpHeaders invalidHeaders = headers(UUID.randomUUID());
        ResponseEntity<Void> invalid = http.exchange(
                url,
                HttpMethod.POST,
                new HttpEntity<>(Map.of("replacementStockLotId", lateLot), invalidHeaders),
                Void.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(overrides()).isZero();

        UUID key = UUID.randomUUID();
        Map<String, Object> body =
                Map.of("replacementStockLotId", lateLot, "reason", "Operator confirmed the accessible pallet");
        ResponseEntity<Void> first =
                http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers(key)), Void.class);
        ResponseEntity<Void> replay =
                http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers(key)), Void.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(overrides()).isEqualTo(1);
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("PICK_OVERRIDDEN");
        assertThat(events(PickOverridden.class)).hasSize(1);
    }

    @Test
    void candidateReadNeedsOnlyWhsPickAndDoesNotExposeIneligibleOrSiblingLocationLots() {
        Pick pick = openPick("4");
        UUID lateLot = lot(warehouse, late);
        UUID damagedBatch = fixture.batch(sku, OWNER, "CAND-DAMAGED", LocalDate.of(2027, 8, 1));
        post(warehouse, damagedBatch, "10", "800", LotCondition.DAMAGED);
        UUID expiredBatch = fixture.batch(sku, OWNER, "CAND-EXPIRED", LocalDate.of(2025, 1, 1));
        post(warehouse, expiredBatch, "10", "800");
        UUID otherSkuBatch = fixture.batch(otherSku, OWNER, "CAND-OTHER-SKU", LocalDate.of(2027, 8, 1));
        post(warehouse, otherSkuBatch, "10", "800");
        UUID shortBatch = fixture.batch(sku, OWNER, "CAND-SHORT", LocalDate.of(2027, 8, 1));
        post(warehouse, shortBatch, "3", "800");
        UUID reservedBatch = fixture.batch(sku, OWNER, "CAND-RESERVED", LocalDate.of(2027, 10, 1));
        post(warehouse, reservedBatch, "10", "800");
        reserve.handle(
                new ReserveDelivery(
                        Ids.next(),
                        OWNER,
                        List.of(new ReserveDelivery.Line(Ids.next(), sku, reservedBatch, new BigDecimal("7")))),
                own(OWNER));
        post(otherWarehouse, late, "10", "950");

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
        UUID pickList = reserve.handle(
                new ReserveDelivery(
                        delivery, OWNER, List.of(new ReserveDelivery.Line(Ids.next(), sku, null, new BigDecimal(qty)))),
                own(OWNER));
        PickListView.Pick line =
                queries.pickList(delivery, own(OWNER)).orElseThrow().lines().get(0);
        kernel.reset();
        return new Pick(delivery, pickList, line.pickLineId(), line.stockLotId());
    }

    private UUID runOverride(Pick pick, UUID replacementLotId, String reason) {
        return runOverride(pick.deliveryNoteId(), pick.pickLineId(), replacementLotId, reason, own(OWNER));
    }

    private UUID runOverride(
            UUID deliveryNoteId, UUID pickLineId, UUID replacementLotId, String reason, ScopeContext scope) {
        return override.handle(new OverridePick(deliveryNoteId, pickLineId, replacementLotId, reason), scope);
    }

    private UUID post(UUID location, UUID batch, String qty, String cost) {
        return post(location, batch, qty, cost, LotCondition.GOOD);
    }

    private UUID post(UUID location, UUID batch, String qty, String cost, LotCondition condition) {
        move(location, batch, condition, MovementType.RECEIPT, qty, cost);
        return lot(location, batch, condition);
    }

    private void move(
            UUID location, UUID batch, LotCondition condition, MovementType movementType, String qty, String cost) {
        ScopeContext scope = own(OWNER);
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
                                        movementType,
                                        new BigDecimal(qty),
                                        cost == null ? null : new BigDecimal(cost),
                                        null))),
                        scope));
    }

    private UUID lot(UUID location, UUID batch) {
        return lot(location, batch, LotCondition.GOOD);
    }

    private UUID lot(UUID location, UUID batch, LotCondition condition) {
        return superuserJdbc()
                .queryForObject(
                        "select stock_lot_id from inventory.stock_lot"
                                + " where location_id = ? and batch_id = ? and condition = ?",
                        UUID.class,
                        location,
                        batch,
                        condition.name());
    }

    private BigDecimal onHand(UUID batch) {
        return superuserJdbc()
                .queryForObject(
                        "select qty_on_hand from inventory.stock_lot where location_id = ? and batch_id = ?",
                        BigDecimal.class,
                        warehouse,
                        batch);
    }

    private int overrides() {
        return superuserJdbc().queryForObject("select count(*) from inventory.pick_override", Integer.class);
    }

    private HttpHeaders headers(UUID idempotencyKey) {
        HttpHeaders headers = TestIdentityProvider.entityWideHeaders(InventoryFixture.USER, OWNER);
        headers.set("Idempotency-Key", idempotencyKey.toString());
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }

    private void assertProblem(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ProblemException.class)
                .extracting(error -> ((ProblemException) error).messageId())
                .isEqualTo(code);
    }

    private static String outcome(CountDownLatch start, Runnable action) throws InterruptedException {
        start.await();
        try {
            action.run();
            return "OK";
        } catch (ProblemException problem) {
            return problem.messageId();
        }
    }

    private static ScopeContext federationView(UUID entity) {
        Scope scope = new Scope(entity, null);
        return new ScopeContext(
                InventoryFixture.USER,
                null,
                entity,
                List.of(scope),
                scope,
                PolicyClass.FEDERATION_VIEW,
                java.util.Set.of(),
                null,
                java.util.Locale.ENGLISH,
                null);
    }

    private <T extends DomainEvent> List<T> events(Class<T> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private record Pick(UUID deliveryNoteId, UUID pickListId, UUID pickLineId, UUID originalLotId) {}
}
