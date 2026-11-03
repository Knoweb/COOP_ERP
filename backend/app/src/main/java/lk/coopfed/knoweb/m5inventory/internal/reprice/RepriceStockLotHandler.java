package lk.coopfed.knoweb.m5inventory.internal.reprice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.RepriceStockLot;
import lk.coopfed.knoweb.m5inventory.api.StockRepriced;
import lk.coopfed.knoweb.m5inventory.api.StockRepricingResult;
import lk.coopfed.knoweb.m5inventory.internal.ledger.CostService;
import lk.coopfed.knoweb.m5inventory.internal.ledger.CostService.CostRow;
import lk.coopfed.knoweb.m5inventory.internal.reprice.RepriceStore.LotRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reprices a stock lot or part of it with new unit cost and optional condition change (PGL-M2-03).
 *
 * <p>Guards:
 * <ul>
 *   <li>Scope must be OWN</li>
 *   <li>Location must match scope location if scope is location-bound</li>
 *   <li>Lot must exist under caller's scope and have positive quantity on hand</li>
 *   <li>Reprice quantity must be greater than zero and not exceed quantity on hand</li>
 *   <li>New unit cost must be greater than or equal to zero</li>
 *   <li>Reason must not be blank</li>
 * </ul>
 *
 * <p>Conserves physical inventory quantities without intake manipulation, revalues entity weighted
 * average cost per IAS 2, and preserves full lot traceability.
 */
@Service
@CommandHandler(permission = "inv.stock.reprice")
public class RepriceStockLotHandler implements Handles<RepriceStockLot, StockRepricingResult> {

    public static final String AUDIT_REPRICED = "STOCK_REPRICED";

    private final RepriceStore store;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    public RepriceStockLotHandler(
            RepriceStore store, JdbcTemplate jdbc, Clock clock, AuditFacade audit, EventPublisher events) {
        this.store = store;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public StockRepricingResult handle(RepriceStockLot command, ScopeContext scope) {
        // ---- 1. Guards ---------------------------------------------------------------------
        if (scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("m5.scope.own_required");
        }

        if (command.stockLotId() == null) {
            throw new ProblemException("m5.reprice.not_found");
        }

        LotRecord sourceLot =
                store.findLot(command.stockLotId()).orElseThrow(() -> new ProblemException("m5.reprice.not_found"));

        if (scope.locationId() != null && !Objects.equals(scope.locationId(), sourceLot.locationId())) {
            throw new ProblemException("m5.location.not_in_scope");
        }

        if (sourceLot.qtyOnHand() == null || sourceLot.qtyOnHand().signum() <= 0) {
            throw new ProblemException("m5.reprice.not_found");
        }

        if (command.qty() == null || command.qty().signum() <= 0) {
            throw new ProblemException("m5.reprice.invalid_qty");
        }

        if (command.qty().compareTo(sourceLot.qtyOnHand()) > 0) {
            throw new ProblemException("m5.reprice.qty_exceeded");
        }

        if (command.newUnitCost() == null || command.newUnitCost().signum() < 0) {
            throw new ProblemException("m5.reprice.invalid_cost");
        }

        if (command.reason() == null || command.reason().isBlank()) {
            throw new ProblemException("m5.reprice.reason_required");
        }

        LotCondition targetCondition =
                command.targetCondition() != null ? command.targetCondition() : sourceLot.condition();

        // ---- 2. Mutation -------------------------------------------------------------------
        Instant now = clock.instant();
        UUID repricingId = UUID.randomUUID();
        BigDecimal newUnitCost = command.newUnitCost().setScale(CostService.COST_SCALE, CostService.ROUNDING);
        BigDecimal previousUnitCost = sourceLot.unitCost().setScale(CostService.COST_SCALE, CostService.ROUNDING);

        BigDecimal valueAdjustment =
                command.qty().multiply(newUnitCost.subtract(previousUnitCost)).setScale(2, RoundingMode.HALF_UP);

        // Revalue moving weighted average for (entity, SKU)
        store.lockEntityCost(sourceLot.ownerEntityId(), sourceLot.skuId()).ifPresent(currentCost -> {
            CostRow revalued = CostService.revalue(currentCost, command.qty(), previousUnitCost, newUnitCost);
            jdbc.update(
                    """
                            update inventory.entity_sku_cost
                               set avg_cost = ?, updated_at = ?
                             where owner_entity_id = ? and sku_id = ?
                            """,
                    revalued.avgCost(),
                    Timestamp.from(now),
                    sourceLot.ownerEntityId(),
                    sourceLot.skuId());
        });

        // Determine target lot ID if condition changes
        UUID targetLotId = null;
        var existingTargetLot = targetCondition != sourceLot.condition()
                ? store.findLotByCondition(sourceLot.locationId(), sourceLot.batchId(), targetCondition)
                : java.util.Optional.<LotRecord>empty();
        if (targetCondition != sourceLot.condition()) {
            targetLotId = existingTargetLot.map(LotRecord::stockLotId).orElseGet(UUID::randomUUID);
        }

        // 1. Insert immutable audit ledger record FIRST (required by database security definer procedure)
        jdbc.update(
                """
                insert into inventory.stock_repricing
                    (repricing_id, owner_entity_id, location_id, stock_lot_id, sku_id, batch_id,
                     condition, qty_affected, previous_unit_cost, new_unit_cost, target_condition,
                     target_lot_id, value_adjustment, reason, repriced_by, repriced_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                repricingId,
                sourceLot.ownerEntityId(),
                sourceLot.locationId(),
                sourceLot.stockLotId(),
                sourceLot.skuId(),
                sourceLot.batchId(),
                sourceLot.condition().name(),
                command.qty(),
                previousUnitCost,
                newUnitCost,
                targetCondition.name(),
                targetLotId,
                valueAdjustment,
                command.reason().trim(),
                scope.userId(),
                Timestamp.from(now));

        boolean fullReprice = command.qty().compareTo(sourceLot.qtyOnHand()) == 0;

        // 2. Perform lot mutations (privileged unit_cost update requires repricingId from step 1)
        if (targetCondition == sourceLot.condition()) {
            if (fullReprice) {
                // Full repricing in-place: update lot cost via SECURITY DEFINER procedure linked to audit
                jdbc.queryForObject(
                        "select inventory.apply_lot_reprice(?, ?, ?)",
                        Boolean.class,
                        repricingId,
                        sourceLot.stockLotId(),
                        newUnitCost);
            } else {
                // Partial repricing in-place: blend lot unit cost via SECURITY DEFINER procedure linked to audit
                BigDecimal remainingQty = sourceLot.qtyOnHand().subtract(command.qty());
                BigDecimal blendedCost = command.qty()
                        .multiply(newUnitCost)
                        .add(remainingQty.multiply(previousUnitCost))
                        .divide(sourceLot.qtyOnHand(), CostService.COST_SCALE, CostService.ROUNDING);
                jdbc.queryForObject(
                        "select inventory.apply_lot_reprice(?, ?, ?)",
                        Boolean.class,
                        repricingId,
                        sourceLot.stockLotId(),
                        blendedCost);
            }
        } else {
            // Condition change (e.g. GOOD -> DAMAGED): deduct qty from source lot
            if (fullReprice) {
                jdbc.update(
                        "update inventory.stock_lot set qty_on_hand = ? where stock_lot_id = ?",
                        BigDecimal.ZERO.setScale(CostService.QTY_SCALE),
                        sourceLot.stockLotId());
            } else {
                jdbc.update(
                        "update inventory.stock_lot set qty_on_hand = ? where stock_lot_id = ?",
                        sourceLot.qtyOnHand().subtract(command.qty()),
                        sourceLot.stockLotId());
            }

            // Move into target condition lot
            if (existingTargetLot.isPresent()) {
                LotRecord target = existingTargetLot.get();
                BigDecimal newTargetQty = target.qtyOnHand().add(command.qty());
                BigDecimal targetBlended = target.qtyOnHand()
                        .multiply(target.unitCost())
                        .add(command.qty().multiply(newUnitCost))
                        .divide(newTargetQty, CostService.COST_SCALE, CostService.ROUNDING);
                jdbc.update(
                        "update inventory.stock_lot set qty_on_hand = ? where stock_lot_id = ?",
                        newTargetQty,
                        target.stockLotId());
                jdbc.queryForObject(
                        "select inventory.apply_lot_reprice(?, ?, ?)",
                        Boolean.class,
                        repricingId,
                        target.stockLotId(),
                        targetBlended);
            } else {
                jdbc.update(
                        """
                        insert into inventory.stock_lot
                            (stock_lot_id, owner_entity_id, location_id, batch_id, sku_id,
                             expiry_date, condition, qty_on_hand, unit_cost, received_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        targetLotId,
                        sourceLot.ownerEntityId(),
                        sourceLot.locationId(),
                        sourceLot.batchId(),
                        sourceLot.skuId(),
                        sourceLot.expiryDate(),
                        targetCondition.name(),
                        command.qty(),
                        newUnitCost,
                        Timestamp.from(now));
            }
        }

        // ---- 3. Audit ----------------------------------------------------------------------
        audit.record(
                AUDIT_REPRICED,
                Subject.of("stock_lot", sourceLot.stockLotId()),
                null,
                Map.of(
                        "repricingId", repricingId,
                        "stockLotId", sourceLot.stockLotId(),
                        "skuId", sourceLot.skuId(),
                        "batchId", sourceLot.batchId(),
                        "qtyAffected", command.qty(),
                        "previousUnitCost", previousUnitCost,
                        "newUnitCost", newUnitCost,
                        "valueAdjustment", valueAdjustment,
                        "reason", command.reason().trim()),
                scope);

        // ---- 4. Domain Event ---------------------------------------------------------------
        events.publish(new StockRepriced(
                repricingId,
                sourceLot.ownerEntityId(),
                sourceLot.locationId(),
                sourceLot.stockLotId(),
                sourceLot.skuId(),
                sourceLot.batchId(),
                command.qty(),
                previousUnitCost,
                newUnitCost,
                valueAdjustment));

        return new StockRepricingResult(
                repricingId,
                sourceLot.stockLotId(),
                sourceLot.skuId(),
                sourceLot.batchId(),
                sourceLot.condition(),
                command.qty(),
                previousUnitCost,
                newUnitCost,
                targetCondition,
                targetLotId,
                valueAdjustment,
                command.reason().trim(),
                scope.userId(),
                now);
    }
}
