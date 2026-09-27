package lk.coopfed.knoweb.m5inventory.internal.consumers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.StockReceived;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ApplyReceipt (25A section 6.2, "grn.confirmed.v1: for line: post RECEIPT (location, batch, GOOD,
 * received - damaged, cost=line.unitCost) and RECEIPT (DAMAGED, damaged) when &gt; 0"; doc 25 flow
 * 6.1). Ownership passed to the receiver when it confirmed the GRN (AGENTS.md idea 2); this puts
 * the goods into its lots, in the receiver's scope.
 *
 * <p>Guards: the receiver's OWN scope ({@code m5.grn.receiver_mismatch}); a receiving location
 * ({@code m5.grn.location_required}); each line's quantities (received and damaged zero or more,
 * damaged at most received, a batch and a cost where something was received:
 * {@code m5.grn.line_invalid}). A GRN whose movements exist already is not applied twice (the
 * consumer's inbox is the first guard against a redelivery, this is the second).
 *
 * <p>Mutation: the ledger's RECEIPT movements, citing the GRN. Audit {@code STOCK_RECEIVED};
 * event {@code stock.received.v1} (and the ledger's {@code stock.moved.v1} per movement).
 *
 * <p>Permission: the system applies it with no user, so none is checked (as for M2's thumbnail
 * job); {@code inv.stock.receive} is the code of the receiving read of the slice
 * ({@code GET /v1/inventory/receipts/{grnId}}), which a user would hold to see it.
 */
@Service
@CommandHandler(permission = "inv.stock.receive")
class ApplyGrnReceiptHandler implements Handles<ApplyGrnReceipt, Integer> {

    static final String AUDIT_RECEIVED = "STOCK_RECEIVED";

    private final StockLedger ledger;
    private final ConsumerStore store;
    private final AuditFacade audit;
    private final EventPublisher events;

    ApplyGrnReceiptHandler(StockLedger ledger, ConsumerStore store, AuditFacade audit, EventPublisher events) {
        this.ledger = ledger;
        this.store = store;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Integer handle(ApplyGrnReceipt command, ScopeContext scope) {
        ConsumerGuards.requireScopeOf(command.receiverEntityId(), scope, "m5.grn.receiver_mismatch");
        if (command.receiverLocationId() == null) {
            throw new ProblemException("m5.grn.location_required");
        }
        for (ApplyGrnReceipt.Line line : command.lines()) {
            requireLine(line);
        }
        if (store.movementsCiting(command.grnId()) > 0) {
            return 0;
        }

        List<Movement> movements = new ArrayList<>();
        for (ApplyGrnReceipt.Line line : command.lines()) {
            BigDecimal damaged = line.damagedQty() == null ? BigDecimal.ZERO : line.damagedQty();
            BigDecimal good = line.receivedQty().subtract(damaged);
            if (good.signum() > 0) {
                movements.add(receipt(command, line, LotCondition.GOOD, good));
            }
            if (damaged.signum() > 0) {
                movements.add(receipt(command, line, LotCondition.DAMAGED, damaged));
            }
        }
        if (!movements.isEmpty()) {
            ledger.post(new PostMovements(command.grnId(), command.confirmedAt(), null, movements), scope);
        }

        audit.record(
                AUDIT_RECEIVED,
                Subject.of("document", command.grnId()),
                null,
                Map.of(
                        "grnId",
                        command.grnId(),
                        "locationId",
                        command.receiverLocationId(),
                        "movements",
                        movements.size()),
                scope);
        events.publish(
                new StockReceived(command.grnId(), scope.entityId(), command.receiverLocationId(), movements.size()));
        return movements.size();
    }

    private static Movement receipt(
            ApplyGrnReceipt command, ApplyGrnReceipt.Line line, LotCondition condition, BigDecimal qty) {
        return new Movement(
                command.receiverLocationId(),
                line.batchId(),
                condition,
                MovementType.RECEIPT,
                qty,
                line.unitCost(),
                line.lineId());
    }

    private static void requireLine(ApplyGrnReceipt.Line line) {
        BigDecimal received = line.receivedQty();
        BigDecimal damaged = line.damagedQty() == null ? BigDecimal.ZERO : line.damagedQty();
        boolean valid = received != null
                && received.signum() >= 0
                && damaged.signum() >= 0
                && damaged.compareTo(received) <= 0
                && (received.signum() == 0
                        || (line.batchId() != null
                                && line.unitCost() != null
                                && line.unitCost().signum() >= 0));
        if (!valid) {
            throw new ProblemException("m5.grn.line_invalid", Map.of("lineId", String.valueOf(line.lineId())));
        }
    }
}
