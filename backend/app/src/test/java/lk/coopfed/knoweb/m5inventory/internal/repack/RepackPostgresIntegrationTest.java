package lk.coopfed.knoweb.m5inventory.internal.repack;

import static lk.coopfed.knoweb.m5inventory.InventoryFixture.at;
import static lk.coopfed.knoweb.m5inventory.InventoryFixture.own;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.DefineRecipe;
import lk.coopfed.knoweb.m5inventory.api.ExecuteRepack;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.RecipeDefined;
import lk.coopfed.knoweb.m5inventory.api.RepackExecuted;
import lk.coopfed.knoweb.m5inventory.api.RepackReversed;
import lk.coopfed.knoweb.m5inventory.api.RetireRecipe;
import lk.coopfed.knoweb.m5inventory.api.ReverseRepack;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.RepackView;
import lk.coopfed.knoweb.m5inventory.query.StockControlQueries;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Recipes and repacks (25A section 6.3; doc 25 section 3.6, flow 6.5; doc 13 scenario 3): 50 kg of
 * loose rice bought for 9,500 is repacked by a recipe expecting 2 % loss into 49 packs, each at
 * 9,500 / 49 = 193.8776; the output batch is registered in M2; while the packs are untouched the
 * repack is reversed and the loose rice comes back exactly; after a sale it is not.
 */
class RepackPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e67e-0000-7000-8000-000000000002");

    @Autowired
    DefineRecipeHandler define;

    @Autowired
    RetireRecipeHandler retire;

    @Autowired
    ExecuteRepackHandler execute;

    @Autowired
    ReverseRepackHandler reverse;

    @Autowired
    StockControlQueries control;

    @Autowired
    InventoryQueries inventory;

    @Autowired
    StockLedger ledger;

    @Autowired
    OuterCommand outer;

    private InventoryFixture fixture;
    private UUID stores;
    private UUID loose;
    private UUID looseBatch;
    private UUID pack;

    @BeforeEach
    void arrange() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        fixture.entity(MPCS, "M5RP", "MPCS");
        stores = fixture.location(MPCS, "WAREHOUSE");
        loose = fixture.plainSku(MPCS, "RICE LOOSE", "PURCHASED");
        looseBatch = fixture.batch(loose, MPCS, "L1", LocalDate.of(2027, 6, 30));
        pack = fixture.plainSku(MPCS, "RICE 1KG PACK", "REPACK_OUTPUT");
        post(MovementType.RECEIPT, looseBatch, "50", "190");
        kernel.reset();
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void docThirteenScenarioThreeRepacksFiftyKiloIntoFortyNinePacksAtTheConsumedCost() {
        UUID recipe = define.handle(recipe("R-012 rice 1 kg"), own(MPCS));
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("RECIPE_DEFINED");
        assertThat(events(RecipeDefined.class)).hasSize(1);

        kernel.reset();
        UUID id = execute.handle(
                new ExecuteRepack(recipe, stores, looseBatch, new BigDecimal("50"), new BigDecimal("49"), null),
                at(MPCS, stores));

        RepackView done = control.repack(id, own(MPCS)).orElseThrow();
        assertThat(done.status()).isEqualTo("EXECUTED");
        assertThat(done.expectedOutputQty()).isEqualByComparingTo("49");
        assertThat(done.varianceQty()).isEqualByComparingTo("0");
        assertThat(done.inputUnitCost()).isEqualByComparingTo("190");
        assertThat(done.outputUnitCost()).isEqualByComparingTo("193.8776");
        assertThat(onHand(loose)).isEqualByComparingTo("0");
        assertThat(onHand(pack)).isEqualByComparingTo("49");
        assertThat(inventory.entityAverageCost(pack, own(MPCS)).orElseThrow().avgCost())
                .isEqualByComparingTo("193.8776");
        assertThat(inventory.movementsOf(id, own(MPCS)))
                .extracting(m -> m.movementType())
                .containsExactly("REPACK_CONSUME", "REPACK_PRODUCE");
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("REPACK_EXECUTED", "STOCK_POSTED");
        assertThat(events(RepackExecuted.class)).singleElement().satisfies(e -> assertThat(e.outputBatchId())
                .isEqualTo(done.outputBatchId()));
    }

    @Test
    void aWrongRecipeIsReversedWhileThePacksAreUntouchedAndTheLooseRiceComesBack() {
        UUID recipe = define.handle(recipe("R-012 rice 1 kg"), own(MPCS));
        UUID id = execute.handle(
                new ExecuteRepack(recipe, stores, looseBatch, new BigDecimal("20"), new BigDecimal("19"), null),
                own(MPCS));
        assertThat(control.repack(id, own(MPCS)).orElseThrow().varianceQty()).isEqualByComparingTo("0.6");

        kernel.reset();
        assertProblem(() -> reverse.handle(new ReverseRepack(id, " "), own(MPCS)), "m5.reason_required");
        reverse.handle(new ReverseRepack(id, "Should have been 5 kg packs"), own(MPCS));

        RepackView reversed = control.repack(id, own(MPCS)).orElseThrow();
        assertThat(reversed.status()).isEqualTo("REVERSED");
        assertThat(reversed.reversalReason()).isEqualTo("Should have been 5 kg packs");
        assertThat(onHand(loose)).isEqualByComparingTo("50");
        assertThat(onHand(pack)).isEqualByComparingTo("0");
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .contains("REPACK_REVERSED");
        assertThat(events(RepackReversed.class)).hasSize(1);
        assertProblem(() -> reverse.handle(new ReverseRepack(id, "again"), own(MPCS)), "m5.repack.already_reversed");
    }

    @Test
    void aRepackIsNotReversedAfterItsPacksWereSold() {
        UUID recipe = define.handle(recipe("R-012 rice 1 kg"), own(MPCS));
        UUID id = execute.handle(
                new ExecuteRepack(recipe, stores, looseBatch, new BigDecimal("50"), new BigDecimal("49"), null),
                own(MPCS));
        UUID outputBatch = control.repack(id, own(MPCS)).orElseThrow().outputBatchId();
        post(MovementType.SALE, outputBatch, "-12", null);

        kernel.reset();
        assertProblem(() -> reverse.handle(new ReverseRepack(id, "wrong"), own(MPCS)), "m5.repack.output_touched");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(onHand(pack)).isEqualByComparingTo("37");
    }

    @Test
    void everyGuardRefusesWithNothingCommitted() {
        assertProblem(
                () -> define.handle(
                        new DefineRecipe(" ", loose, BigDecimal.TEN, pack, BigDecimal.TEN, null), own(MPCS)),
                "m5.recipe.name_required");
        assertProblem(
                () -> define.handle(
                        new DefineRecipe("x", loose, BigDecimal.TEN, pack, BigDecimal.TEN, new BigDecimal("100")),
                        own(MPCS)),
                "m5.recipe.invalid");
        assertProblem(
                () -> define.handle(
                        new DefineRecipe("x", loose, BigDecimal.TEN, loose, BigDecimal.TEN, null), own(MPCS)),
                "m5.recipe.same_sku");
        assertProblem(
                () -> define.handle(
                        new DefineRecipe("x", pack, BigDecimal.TEN, loose, BigDecimal.TEN, null), own(MPCS)),
                "m5.recipe.output_not_repack");
        assertProblem(
                () -> define.handle(
                        new DefineRecipe("x", loose, BigDecimal.TEN, Ids.next(), BigDecimal.TEN, null), own(MPCS)),
                "m5.recipe.sku_inactive");
        UUID recipe = define.handle(recipe("R-012 rice 1 kg"), own(MPCS));
        kernel.reset();
        assertProblem(() -> define.handle(recipe("r-012 RICE 1 kg"), own(MPCS)), "m5.recipe.name_taken");
        assertProblem(
                () -> execute.handle(
                        new ExecuteRepack(recipe, stores, looseBatch, new BigDecimal("51"), BigDecimal.ONE, null),
                        own(MPCS)),
                "m5.repack.insufficient_stock");
        assertProblem(
                () -> execute.handle(
                        new ExecuteRepack(recipe, stores, looseBatch, BigDecimal.ZERO, BigDecimal.ONE, null),
                        own(MPCS)),
                "m5.repack.qty_invalid");
        UUID otherBatch = fixture.batch(pack, MPCS, "P0", null);
        assertProblem(
                () -> execute.handle(
                        new ExecuteRepack(recipe, stores, otherBatch, BigDecimal.ONE, BigDecimal.ONE, null), own(MPCS)),
                "m5.repack.input_mismatch");
        assertProblem(
                () -> execute.handle(
                        new ExecuteRepack(Ids.next(), stores, looseBatch, BigDecimal.ONE, BigDecimal.ONE, null),
                        own(MPCS)),
                "m5.recipe.not_found");
        assertProblem(() -> reverse.handle(new ReverseRepack(Ids.next(), "x"), own(MPCS)), "m5.repack.not_found");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        retire.handle(new RetireRecipe(recipe), own(MPCS));
        assertThat(kernel.committedAudit())
                .extracting(KernelRecorder.AuditRecord::eventType)
                .containsExactly("RECIPE_RETIRED");
        assertProblem(
                () -> execute.handle(
                        new ExecuteRepack(recipe, stores, looseBatch, BigDecimal.ONE, BigDecimal.ONE, null), own(MPCS)),
                "m5.recipe.retired");
        assertProblem(() -> retire.handle(new RetireRecipe(recipe), own(MPCS)), "m5.recipe.retired");
        // A retired name is free again.
        define.handle(recipe("R-012 rice 1 kg"), own(MPCS));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private DefineRecipe recipe(String name) {
        return new DefineRecipe(name, loose, new BigDecimal("50"), pack, new BigDecimal("50"), new BigDecimal("2"));
    }

    private void post(MovementType type, UUID batch, String qty, String cost) {
        ScopeContext scope = own(MPCS);
        outer.run(
                scope,
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
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

    private BigDecimal onHand(UUID sku) {
        return inventory.balances(stores, sku, true, own(MPCS)).stream()
                .map(l -> l.qtyOnHand())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
