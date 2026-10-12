package lk.coopfed.knoweb.m5inventory.internal.repack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import lk.coopfed.knoweb.m5inventory.api.RepackReversed;
import lk.coopfed.knoweb.m5inventory.api.ReverseRepack;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ReverseRepack (25A section 6.3: "EXECUTED; output lot qty = produced and no movements against it;
 * reason → REVERSES-linked RPK: negating movements; input lot restored"; doc 25 flow 6.5, the
 * wrong-recipe correction; config inventory.repack_reversal_requires_untouched_output, fixed true).
 *
 * <p>Guards, in order: an OWN scope; the repack visible ({@code m5.repack.not_found}); not reversed
 * ({@code m5.repack.already_reversed}); a reason ({@code m5.reason_required}); the output untouched:
 * its lot holds exactly what was produced and nothing but the repack moved it
 * ({@code m5.repack.output_touched}: after a sale the correction is a write-off or a second recipe).
 *
 * <p>Mutation: REPACK_CONSUME of the whole output at the repack's output cost (an out movement at a
 * given cost, so the output item's average loses exactly what the repack added) and REPACK_PRODUCE
 * of the input quantity back at the cost it left with, both citing the repack; the reversal row (the repack is REVERSED because
 * it exists). Audit {@code REPACK_REVERSED} with the reason, and the cost residual the output
 * item's average could not keep when there is one ({@link #costResidual}; wave 3, M1M2M3M5-23);
 * event {@code repack.reversed.v1}.
 */
@Service
@CommandHandler(permission = "inv.repack.reverse")
class ReverseRepackHandler implements Handles<ReverseRepack, UUID> {

    static final String AUDIT_REVERSED = "REPACK_REVERSED";

    private final RepackStore store;
    private final StockLedger ledger;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    ReverseRepackHandler(
            RepackStore store,
            StockLedger ledger,
            JdbcTemplate jdbc,
            Clock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ReverseRepack command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        RepackStore.Repack repack = store.repack(command.repackId());
        if (repack.reversed()) {
            throw new ProblemException("m5.repack.already_reversed");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw new ProblemException("m5.reason_required");
        }
        if (store.goodOnHand(repack.locationId(), repack.outputBatchId()).compareTo(repack.actualOutputQty()) != 0
                || store.movedByOthers(repack.locationId(), repack.outputBatchId(), repack.repackId())) {
            throw new ProblemException("m5.repack.output_touched");
        }

        String reason = command.reason().strip();
        BigDecimal residual = costResidual(
                store.entityCostOfLot(repack.locationId(), repack.outputBatchId()),
                repack.actualOutputQty(),
                repack.outputUnitCost());
        ledger.post(
                new PostMovements(
                        repack.repackId(),
                        null,
                        null,
                        List.of(
                                new Movement(
                                        repack.locationId(),
                                        repack.outputBatchId(),
                                        LotCondition.GOOD,
                                        MovementType.REPACK_CONSUME,
                                        repack.actualOutputQty().negate(),
                                        // out at the repack's own output cost, not the output
                                        // item's average of today: the reversal removes exactly
                                        // the value the repack added (wave 2, M5-17)
                                        repack.outputUnitCost(),
                                        null),
                                new Movement(
                                        repack.locationId(),
                                        repack.inputBatchId(),
                                        LotCondition.GOOD,
                                        MovementType.REPACK_PRODUCE,
                                        repack.inputQty(),
                                        repack.inputUnitCost(),
                                        null))),
                scope);
        jdbc.update(
                """
                insert into inventory.repack_reversal
                    (repack_id, owner_entity_id, location_id, reason, reversed_by, reversed_at)
                values (?, ?, ?, ?, ?, ?)
                """,
                repack.repackId(),
                repack.ownerEntityId(),
                repack.locationId(),
                reason,
                scope.userId(),
                Timestamp.from(clock.instant()));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "REVERSED");
        if (residual.signum() != 0) {
            after.put("costResidual", residual.toPlainString());
        }
        audit.record(
                AUDIT_REVERSED,
                Subject.of("repack", repack.repackId()),
                Map.of("status", "EXECUTED"),
                after,
                scope,
                reason);
        events.publish(new RepackReversed(repack.repackId(), repack.ownerEntityId(), repack.locationId()));
        return repack.repackId();
    }

    /**
     * The value the output item's average cannot keep (wave 3, M1M2M3M5-23; the clamp itself is as
     * decided for M5-17): taking q packs out at the repack's cost c from an entity holding Q at an
     * average a leaves the value Q*a - q*c. When that leaves no quantity, or a negative value that
     * the average clamps at zero, the ledger keeps no trace of it, so the audit records it,
     * rounded to the cent; zero when the average absorbs it. The cost row is read before the
     * posting, without a lock: the figure is a record for a person, not a balance.
     */
    static BigDecimal costResidual(RepackStore.ItemCost before, BigDecimal outQty, BigDecimal outUnitCost) {
        BigDecimal remainingQty = before.qtyOnHand().subtract(outQty);
        BigDecimal remainingValue =
                before.qtyOnHand().multiply(before.avgCost()).subtract(outQty.multiply(outUnitCost));
        if (remainingQty.signum() <= 0 || remainingValue.signum() < 0) {
            return remainingValue.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }
}
