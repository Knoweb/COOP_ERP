package lk.coopfed.knoweb.m5inventory.internal.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import lk.coopfed.knoweb.m5inventory.api.MovementType;

/**
 * The moving weighted average per (entity, SKU) (25A section 6.1, "CostService.apply"; doc 25
 * section 3.3; E-03, IAS 2). Arithmetic only: the ledger locks the row, asks this class for the
 * next state, and writes it. Kept pure so the property tests can drive it with thousands of
 * random sequences without a database.
 *
 * <p>The rules:
 * <ul>
 *   <li>an intake at cost (RECEIPT, OPENING_BALANCE, REPACK_PRODUCE) re-averages:
 *       {@code avg = (qty × avg + qtyIn × costIn) / (qty + qtyIn)}, cost scale 4, half up;
 *   <li>when the entity holds nothing, or owes stock (an oversell took it below zero), the intake's
 *       cost becomes the average: there is no stock on hand whose cost it could average with,
 *       and averaging with a negative quantity would give a meaningless, even negative, figure.
 *       25A says only "avg never negative"; this is how it holds;
 *   <li>every other movement (a transfer in included: the stock stayed inside the entity) changes
 *       the quantity and keeps the average, the last known one when the quantity reaches zero.
 * </ul>
 * The shared engine has no cost arithmetic yet (it holds Money and Quantity only), so the scale
 * and rounding are written here once, as {@link #COST_SCALE} and {@link #ROUNDING}.
 */
final class CostService {

    static final int COST_SCALE = 4;
    static final int QTY_SCALE = 3;
    static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** The average and the quantity of one (entity, SKU). */
    record CostRow(BigDecimal qtyOnHand, BigDecimal avgCost) {

        static CostRow empty() {
            return new CostRow(BigDecimal.ZERO.setScale(QTY_SCALE), BigDecimal.ZERO.setScale(COST_SCALE));
        }
    }

    private CostService() {}

    /** The row after one movement of {@code qtyDelta} (signed) at {@code unitCost} (intakes only). */
    static CostRow apply(CostRow row, MovementType type, BigDecimal qtyDelta, BigDecimal unitCost) {
        BigDecimal qty = row.qtyOnHand().add(qtyDelta).setScale(QTY_SCALE, ROUNDING);

        if (!type.reaverages()) {
            return new CostRow(qty, row.avgCost());
        }
        if (row.qtyOnHand().signum() <= 0) {
            return new CostRow(qty, unitCost.setScale(COST_SCALE, ROUNDING));
        }
        BigDecimal value = row.qtyOnHand().multiply(row.avgCost()).add(qtyDelta.multiply(unitCost));
        BigDecimal avg = value.divide(row.qtyOnHand().add(qtyDelta), COST_SCALE, ROUNDING);
        return new CostRow(qty, avg);
    }

    /**
     * The cost a movement carries (25A section 6.1, "costFor"): the caller's for a type that
     * carries its own (an intake, a transfer in at the source lot's cost), else the entity
     * average at that moment.
     */
    static BigDecimal costAtMovement(CostRow row, MovementType type, BigDecimal unitCost) {
        return type.carriesItsOwnCost() ? unitCost.setScale(COST_SCALE, ROUNDING) : row.avgCost();
    }
}
