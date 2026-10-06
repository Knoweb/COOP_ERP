package lk.coopfed.knoweb.m5inventory.internal.ledger;

import java.math.BigDecimal;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;

/**
 * The ledger's guards that need no database (25A section 6.1). Each failure is a message id of
 * {@code i18n/m5inventory/*.json}; a unit test covers every one.
 */
final class LedgerGuards {

    private LedgerGuards() {}

    /** Stock is posted in an owner's scope: the rows are the scope entity's (row-level security says so too). */
    static void requireOwnScope(ScopeContext scope) {
        if (scope == null || scope.policyClass() != PolicyClass.OWN || scope.entityId() == null) {
            throw new ProblemException("m5.scope.own_required");
        }
    }

    static void requireCommand(PostMovements command) {
        if (command == null || command.documentId() == null) {
            throw new ProblemException("m5.ledger.document_required");
        }
        if (command.movements() == null || command.movements().isEmpty()) {
            throw new ProblemException("m5.ledger.movements_required");
        }
        for (Movement m : command.movements()) {
            requireMovement(m);
        }
    }

    static void requireMovement(Movement m) {
        if (m == null
                || m.locationId() == null
                || m.batchId() == null
                || m.condition() == null
                || m.type() == null
                || m.qtyDelta() == null) {
            throw new ProblemException("m5.ledger.movement_incomplete");
        }
        BigDecimal qty = m.qtyDelta().stripTrailingZeros();
        if (qty.signum() == 0 || qty.scale() > CostService.QTY_SCALE) {
            throw new ProblemException(
                    "m5.ledger.qty_invalid", Map.of("qty", m.qtyDelta().toPlainString()));
        }
        MovementType.Direction direction = m.type().direction();
        if ((direction == MovementType.Direction.IN && qty.signum() < 0)
                || (direction == MovementType.Direction.OUT && qty.signum() > 0)) {
            throw new ProblemException(
                    "m5.ledger.sign_invalid", Map.of("type", m.type().name()));
        }
        if (m.type().carriesItsOwnCost()
                && (m.unitCost() == null || m.unitCost().signum() < 0)) {
            throw new ProblemException(
                    "m5.ledger.cost_required", Map.of("type", m.type().name()));
        }
        if (m.unitCost() != null
                && (m.unitCost().signum() < 0
                        || m.unitCost().stripTrailingZeros().scale() > CostService.COST_SCALE)) {
            throw new ProblemException(
                    "m5.ledger.cost_invalid", Map.of("cost", m.unitCost().toPlainString()));
        }
    }
}
