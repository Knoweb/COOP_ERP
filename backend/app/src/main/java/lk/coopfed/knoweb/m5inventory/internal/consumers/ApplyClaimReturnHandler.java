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
import lk.coopfed.knoweb.m5inventory.api.StockReturnedToSeller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ClaimReturn (25A section 6.2: "when returnRequired: post RETURN_TO_SELLER (-qty) from the
 * claim's condition lot"). The claimed goods sit in the GOOD lot the GRN put them in (the damage or
 * expiry was found after the receipt), so the return leaves that lot at the entity average; the
 * ledger lets it go below zero, as a sale may, and flags it (lot.negative).
 *
 * <p>Guards: the buyer's OWN scope ({@code m5.claim.buyer_mismatch}); a location ({@code
 * m5.claim.location_required}); each line a batch and a quantity above zero ({@code
 * m5.claim.line_invalid}). A claim whose movements exist already is not applied twice.
 *
 * <p>Mutation: the ledger's RETURN_TO_SELLER movements, citing the claim. Audit {@code
 * STOCK_RETURNED_TO_SELLER}; event {@code stock.returned_to_seller.v1} (and the ledger's {@code
 * stock.moved.v1}). Applied by the system with no user, so no permission is checked (as
 * ApplyGrnReceipt); {@code inv.stock.receive} is named as the stores' code.
 */
@Service
@CommandHandler(permission = "inv.stock.receive")
class ApplyClaimReturnHandler implements Handles<ApplyClaimReturn, Integer> {

    static final String AUDIT_RETURNED = "STOCK_RETURNED_TO_SELLER";

    private final StockLedger ledger;
    private final ConsumerStore store;
    private final AuditFacade audit;
    private final EventPublisher events;

    ApplyClaimReturnHandler(StockLedger ledger, ConsumerStore store, AuditFacade audit, EventPublisher events) {
        this.ledger = ledger;
        this.store = store;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Integer handle(ApplyClaimReturn command, ScopeContext scope) {
        ConsumerGuards.requireScopeOf(command.buyerEntityId(), scope, "m5.claim.buyer_mismatch");
        if (command.locationId() == null) {
            throw new ProblemException("m5.claim.location_required");
        }
        for (ApplyClaimReturn.Line line : command.lines()) {
            if (line.batchId() == null || line.qty() == null || line.qty().signum() <= 0) {
                throw new ProblemException(
                        "m5.claim.line_invalid", Map.of("claimLineId", String.valueOf(line.claimLineId())));
            }
        }
        if (store.movementsCiting(command.claimId()) > 0) {
            return 0;
        }

        List<Movement> movements = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ApplyClaimReturn.Line line : command.lines()) {
            movements.add(new Movement(
                    command.locationId(),
                    line.batchId(),
                    LotCondition.GOOD,
                    MovementType.RETURN_TO_SELLER,
                    line.qty().negate(),
                    null,
                    line.claimLineId()));
            total = total.add(line.qty());
        }
        if (!movements.isEmpty()) {
            ledger.post(new PostMovements(command.claimId(), null, null, movements), scope);
        }

        audit.record(
                AUDIT_RETURNED,
                Subject.of("document", command.claimId()),
                null,
                Map.of("claimId", command.claimId(), "locationId", command.locationId(), "movements", movements.size()),
                scope);
        events.publish(new StockReturnedToSeller(
                command.claimId(), scope.entityId(), command.locationId(), movements.size(), total));
        return movements.size();
    }
}
