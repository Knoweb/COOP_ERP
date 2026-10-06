package lk.coopfed.knoweb.m5inventory.internal.ledger;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import org.junit.jupiter.api.Test;

/** Every guard of the ledger that needs no database, with its failing case (25A section 9). */
class LedgerGuardsTest {

    private static final UUID ENTITY = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();
    private static final UUID DOCUMENT = UUID.randomUUID();

    @Test
    void theScopeMustBeAnOwnersScopeWithAnEntity() {
        assertProblem(() -> LedgerGuards.requireOwnScope(null), "m5.scope.own_required");
        Scope scope = new Scope(ENTITY, null);
        ScopeContext view = new ScopeContext(
                null,
                null,
                ENTITY,
                List.of(scope),
                scope,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);
        assertProblem(() -> LedgerGuards.requireOwnScope(view), "m5.scope.own_required");
        ScopeContext none = new ScopeContext(
                null, null, null, List.of(), null, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
        assertProblem(() -> LedgerGuards.requireOwnScope(none), "m5.scope.own_required");
        assertThatCode(() -> LedgerGuards.requireOwnScope(ScopeContext.dev(null, ENTITY, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void aPostingCitesADocumentAndHasMovements() {
        assertProblem(() -> LedgerGuards.requireCommand(null), "m5.ledger.document_required");
        assertProblem(
                () -> LedgerGuards.requireCommand(new PostMovements(null, null, null, List.of(receipt("1")))),
                "m5.ledger.document_required");
        assertProblem(
                () -> LedgerGuards.requireCommand(new PostMovements(DOCUMENT, null, null, List.of())),
                "m5.ledger.movements_required");
        assertProblem(
                () -> LedgerGuards.requireCommand(new PostMovements(DOCUMENT, null, null, null)),
                "m5.ledger.movements_required");
    }

    @Test
    void aMovementIsComplete() {
        assertProblem(
                () -> LedgerGuards.requireMovement(new Movement(
                        null, BATCH, LotCondition.GOOD, MovementType.SALE, BigDecimal.ONE.negate(), null, null)),
                "m5.ledger.movement_incomplete");
        assertProblem(
                () -> LedgerGuards.requireMovement(new Movement(
                        LOCATION, null, LotCondition.GOOD, MovementType.SALE, BigDecimal.ONE.negate(), null, null)),
                "m5.ledger.movement_incomplete");
        assertProblem(
                () -> LedgerGuards.requireMovement(
                        new Movement(LOCATION, BATCH, null, MovementType.SALE, BigDecimal.ONE.negate(), null, null)),
                "m5.ledger.movement_incomplete");
        assertProblem(
                () -> LedgerGuards.requireMovement(
                        new Movement(LOCATION, BATCH, LotCondition.GOOD, null, BigDecimal.ONE.negate(), null, null)),
                "m5.ledger.movement_incomplete");
        assertProblem(
                () -> LedgerGuards.requireMovement(
                        new Movement(LOCATION, BATCH, LotCondition.GOOD, MovementType.SALE, null, null, null)),
                "m5.ledger.movement_incomplete");
    }

    @Test
    void aQuantityIsNotZeroAndHasAtMostThreeDecimals() {
        assertProblem(() -> LedgerGuards.requireMovement(receipt("0.000")), "m5.ledger.qty_invalid");
        assertProblem(() -> LedgerGuards.requireMovement(receipt("1.0001")), "m5.ledger.qty_invalid");
        assertThatCode(() -> LedgerGuards.requireMovement(receipt("1.2500"))).doesNotThrowAnyException();
    }

    @Test
    void theSignIsTheOneTheTypeAllows() {
        assertProblem(() -> LedgerGuards.requireMovement(receipt("-1")), "m5.ledger.sign_invalid");
        assertProblem(() -> LedgerGuards.requireMovement(issue(MovementType.SALE, "1")), "m5.ledger.sign_invalid");
        assertProblem(
                () -> LedgerGuards.requireMovement(issue(MovementType.SALE_REVERSAL, "-1")), "m5.ledger.sign_invalid");
        assertThatCode(() -> LedgerGuards.requireMovement(issue(MovementType.COUNT_ADJUST, "1")))
                .doesNotThrowAnyException();
        assertThatCode(() -> LedgerGuards.requireMovement(issue(MovementType.COUNT_ADJUST, "-1")))
                .doesNotThrowAnyException();
    }

    @Test
    void anIntakeCarriesACostOfZeroOrMoreWithAtMostFourDecimals() {
        assertProblem(
                () -> LedgerGuards.requireMovement(new Movement(
                        LOCATION, BATCH, LotCondition.GOOD, MovementType.RECEIPT, BigDecimal.ONE, null, null)),
                "m5.ledger.cost_required");
        assertProblem(
                () -> LedgerGuards.requireMovement(new Movement(
                        LOCATION,
                        BATCH,
                        LotCondition.GOOD,
                        MovementType.TRANSFER_IN,
                        BigDecimal.ONE,
                        new BigDecimal("-0.01"),
                        null)),
                "m5.ledger.cost_required");
        assertProblem(
                () -> LedgerGuards.requireMovement(new Movement(
                        LOCATION,
                        BATCH,
                        LotCondition.GOOD,
                        MovementType.RECEIPT,
                        BigDecimal.ONE,
                        new BigDecimal("1.00001"),
                        null)),
                "m5.ledger.cost_invalid");
        assertThatCode(() -> LedgerGuards.requireMovement(new Movement(
                        LOCATION,
                        BATCH,
                        LotCondition.GOOD,
                        MovementType.RECEIPT,
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        null)))
                .doesNotThrowAnyException();
        // An issue needs none: it is costed at the entity average.
        assertThatCode(() -> LedgerGuards.requireMovement(issue(MovementType.WRITE_OFF, "-2")))
                .doesNotThrowAnyException();
    }

    @Test
    void aSaleReversalCarriesItsSalesCostAndAnOutAtAGivenCostIsNeverNegative() {
        // wave 2, D10: a sale reversal comes back at the original sale's cost, so it must carry one.
        assertProblem(
                () -> LedgerGuards.requireMovement(issue(MovementType.SALE_REVERSAL, "1")), "m5.ledger.cost_required");
        assertProblem(
                () -> LedgerGuards.requireMovement(new Movement(
                        LOCATION,
                        BATCH,
                        LotCondition.GOOD,
                        MovementType.REPACK_CONSUME,
                        BigDecimal.ONE.negate(),
                        new BigDecimal("-1"),
                        null)),
                "m5.ledger.cost_invalid");
        assertThatCode(() -> LedgerGuards.requireMovement(new Movement(
                        LOCATION,
                        BATCH,
                        LotCondition.GOOD,
                        MovementType.REPACK_CONSUME,
                        BigDecimal.ONE.negate(),
                        new BigDecimal("193.8776"),
                        null)))
                .doesNotThrowAnyException();
    }

    private static Movement receipt(String qty) {
        return new Movement(
                LOCATION,
                BATCH,
                LotCondition.GOOD,
                MovementType.RECEIPT,
                new BigDecimal(qty),
                new BigDecimal("10"),
                null);
    }

    private static Movement issue(MovementType type, String qty) {
        return new Movement(LOCATION, BATCH, LotCondition.GOOD, type, new BigDecimal(qty), null, null);
    }

    private static void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String id) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> org.assertj.core.api.Assertions.assertThat(e.messageId())
                                .isEqualTo(id));
    }
}
