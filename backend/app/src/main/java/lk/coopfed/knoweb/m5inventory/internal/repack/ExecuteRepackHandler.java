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
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.BatchRegistration;
import lk.coopfed.knoweb.m2catalogue.api.RegisterBatch;
import lk.coopfed.knoweb.m2catalogue.api.RegisteredBatch;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchView;
import lk.coopfed.knoweb.m5inventory.api.ExecuteRepack;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.PostedMovement;
import lk.coopfed.knoweb.m5inventory.api.RepackExecuted;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ExecuteRepack (25A section 6.3: "recipe ACTIVE; input lots GOOD with qty ≥ recipe input;
 * issuance; consume/produce movements; output cost = consumed cost / actual output; variance =
 * expected − actual; output batch via M2 RegisterBatch (expiry from input batch)"; doc 25 flow 6.5,
 * doc 13 scenario 3: 50 kg at 9,500 into 49 packs at 193.88).
 *
 * <p>Accepted on the architect's delegation: PlanRepack and ExecuteRepack are one step in the back
 * office (the plan is only the recipe and the lot, which the form holds); one input lot per repack;
 * the quantity taken from it is the operator's, and the expected output scales the recipe to it,
 * less the recipe's expected loss. The RPK document is not issued yet: the movements and the
 * output batch cite the repack's id, the synthetic batch number reads {@code S-RPK-<id>-1}.
 *
 * <p>Guards, in order: an OWN scope; the location one of the entity's that the scope reads
 * ({@code m5.location.not_in_scope}); the recipe visible ({@code m5.recipe.not_found}) and ACTIVE
 * ({@code m5.recipe.retired}); both quantities above zero with three decimals at most
 * ({@code m5.repack.qty_invalid}); the input batch known to M2 ({@code m5.batch.not_found}) and of
 * the recipe's input item ({@code m5.repack.input_mismatch}); its GOOD lot at the location
 * holding the quantity ({@code m5.repack.insufficient_stock}); the yield (wave 2, M5-18;
 * {@code inventory.repack_yield_tolerance_pct}, 2 % of the expected output): more packs than the
 * tolerance allows are refused ({@code m5.repack.yield_out_of_range}: sellable stock from
 * nothing), fewer need a reason ({@code m5.repack.yield_reason_required}); M2's own guards on the
 * output batch (an expiry or an MRP where the output item needs one).
 *
 * <p>Mutation: REPACK_CONSUME of the input at the entity average; the output batch registered in
 * M2 with the input batch's expiry; REPACK_PRODUCE of the actual output at the consumed value
 * divided by it (which re-averages the output item); the repack row with the yield variance.
 * Audit {@code REPACK_EXECUTED}, and {@code REPACK_YIELD_EXCEPTION} (REVIEW) with the reason for a
 * shortfall beyond the tolerance; event {@code repack.executed.v1}.
 */
@Service
@CommandHandler(permission = "inv.repack.execute")
class ExecuteRepackHandler implements Handles<ExecuteRepack, UUID> {

    static final String AUDIT_EXECUTED = "REPACK_EXECUTED";
    static final String AUDIT_YIELD_EXCEPTION = "REPACK_YIELD_EXCEPTION";

    private final RepackStore store;
    private final ControlPolicy policy;
    private final BatchQueries batches;
    private final BatchRegistration registration;
    private final StockLedger ledger;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    @SuppressWarnings("java:S107") // the collaborators of one command, each used once
    ExecuteRepackHandler(
            RepackStore store,
            ControlPolicy policy,
            BatchQueries batches,
            BatchRegistration registration,
            StockLedger ledger,
            JdbcTemplate jdbc,
            Clock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.policy = policy;
        this.batches = batches;
        this.registration = registration;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ExecuteRepack command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        policy.requireEntityLocation(command.locationId(), scope);
        RepackStore.Recipe recipe = store.lockRecipe(command.recipeId());
        if (!"ACTIVE".equals(recipe.status())) {
            throw new ProblemException("m5.recipe.retired");
        }
        if (!quantity(command.inputQty()) || !quantity(command.actualOutputQty())) {
            throw new ProblemException("m5.repack.qty_invalid");
        }
        BatchView input = batches.getBatch(command.inputBatchId(), scope)
                .orElseThrow(() -> new ProblemException(
                        "m5.batch.not_found", Map.of("batchId", String.valueOf(command.inputBatchId()))));
        if (!recipe.inputSkuId().equals(input.skuId())) {
            throw new ProblemException("m5.repack.input_mismatch");
        }
        if (store.goodOnHand(command.locationId(), input.batchId()).compareTo(command.inputQty()) < 0) {
            throw new ProblemException("m5.repack.insufficient_stock", Map.of("batchId", input.batchId()));
        }
        BigDecimal expected = command.inputQty()
                .multiply(recipe.outputQty())
                .multiply(BigDecimal.valueOf(100).subtract(recipe.expectedLossPct()))
                .divide(recipe.inputQty().multiply(BigDecimal.valueOf(100)), 3, RoundingMode.HALF_UP);
        // wave 2, M5-18: packs made from nothing are refused; a real shortfall is a loss that must be
        // explained and seen (a till's repack bundle, when it comes, flags and never refuses).
        BigDecimal tolerance = expected.multiply(policy.repackYieldTolerancePct(scope))
                .divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP);
        if (command.actualOutputQty().compareTo(expected.add(tolerance)) > 0) {
            throw new ProblemException(
                    "m5.repack.yield_out_of_range",
                    Map.of(
                            "expected", expected.toPlainString(),
                            "actual", command.actualOutputQty().toPlainString()));
        }
        boolean shortfall = command.actualOutputQty().compareTo(expected.subtract(tolerance)) < 0;
        String reason =
                command.varianceReason() == null || command.varianceReason().isBlank()
                        ? null
                        : command.varianceReason().strip();
        if (shortfall && reason == null) {
            throw new ProblemException(
                    "m5.repack.yield_reason_required",
                    Map.of(
                            "expected", expected.toPlainString(),
                            "actual", command.actualOutputQty().toPlainString()));
        }

        UUID id = Ids.next();
        List<PostedMovement> consumed = ledger.post(
                new PostMovements(
                        id,
                        null,
                        null,
                        List.of(new Movement(
                                command.locationId(),
                                input.batchId(),
                                LotCondition.GOOD,
                                MovementType.REPACK_CONSUME,
                                command.inputQty().negate(),
                                null,
                                null))),
                scope);
        BigDecimal inputCost = consumed.get(0).unitCostAtMovement();
        BigDecimal outputCost =
                command.inputQty().multiply(inputCost).divide(command.actualOutputQty(), 4, RoundingMode.HALF_UP);
        RegisteredBatch output = registration.register(
                new RegisterBatch(
                        recipe.outputSkuId(),
                        null,
                        null,
                        null,
                        input.expiryDate(),
                        command.printedMrp(),
                        id,
                        "RPK-" + id.toString().substring(0, 8),
                        1),
                scope);
        ledger.post(
                new PostMovements(
                        id,
                        null,
                        null,
                        List.of(new Movement(
                                command.locationId(),
                                output.batchId(),
                                LotCondition.GOOD,
                                MovementType.REPACK_PRODUCE,
                                command.actualOutputQty(),
                                outputCost,
                                null))),
                scope);
        BigDecimal variance = expected.subtract(command.actualOutputQty());
        jdbc.update(
                """
                insert into inventory.repack
                    (repack_id, owner_entity_id, location_id, recipe_id, input_batch_id, input_sku_id, input_qty,
                     input_unit_cost, output_sku_id, output_batch_id, expected_output_qty, actual_output_qty,
                     variance_qty, output_unit_cost, executed_by, executed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                scope.entityId(),
                command.locationId(),
                recipe.recipeId(),
                input.batchId(),
                input.skuId(),
                command.inputQty(),
                inputCost,
                recipe.outputSkuId(),
                output.batchId(),
                expected,
                command.actualOutputQty(),
                variance,
                outputCost,
                scope.userId(),
                Timestamp.from(clock.instant()));

        audit.record(
                AUDIT_EXECUTED,
                Subject.of("repack", id),
                null,
                Map.of(
                        "recipeId", recipe.recipeId(),
                        "inputQty", command.inputQty().toPlainString(),
                        "expectedOutputQty", expected.toPlainString(),
                        "actualOutputQty", command.actualOutputQty().toPlainString(),
                        "varianceQty", variance.toPlainString(),
                        "outputUnitCost", outputCost.toPlainString(),
                        "outputBatchId", output.batchId()),
                scope);
        if (shortfall) {
            Map<String, Object> exception = new LinkedHashMap<>();
            exception.put("expectedOutputQty", expected.toPlainString());
            exception.put("actualOutputQty", command.actualOutputQty().toPlainString());
            exception.put("tolerancePct", policy.repackYieldTolerancePct(scope).toPlainString());
            audit.record(AUDIT_YIELD_EXCEPTION, Subject.of("repack", id), null, exception, scope, reason);
        }
        events.publish(new RepackExecuted(
                id,
                scope.entityId(),
                command.locationId(),
                recipe.recipeId(),
                output.batchId(),
                command.actualOutputQty(),
                variance));
        return id;
    }

    private static boolean quantity(BigDecimal qty) {
        return qty != null && qty.signum() > 0 && qty.stripTrailingZeros().scale() <= 3;
    }
}
