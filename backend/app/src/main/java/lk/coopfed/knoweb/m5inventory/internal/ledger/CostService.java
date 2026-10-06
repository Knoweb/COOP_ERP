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
 * <p>The rules (CR-25A-1 item 5; {@code docs/progress/deviations/2026-10-06-wave2-stock-movements.md} (5)):
 * <ul>
 *   <li><b>in at its own cost</b>: a type that carries its own cost (RECEIPT, OPENING_BALANCE,
 *       REPACK_PRODUCE, TRANSFER_IN, SALE_REVERSAL) re-averages:
 *       {@code avg = (qty × avg + qtyIn × costIn) / (qty + qtyIn)}, cost scale 4, half up. A
 *       TRANSFER_IN comes back at the cost its TRANSFER_OUT left with, so the pair conserves value
 *       and an intake that landed while the stock was in transit is averaged against the right
 *       base (M5-06);
 *   <li>when the entity holds nothing, or owes stock (an oversell took it below zero), the intake's
 *       cost becomes the average: there is no stock on hand whose cost it could average with,
 *       and averaging with a negative quantity would give a meaningless, even negative, figure.
 *       25A says only "avg never negative"; this is how it holds;
 *   <li><b>out at a given cost</b>: an out movement the caller gives a cost (the repack reversal's
 *       REPACK_CONSUME at the repack's output cost, M5-17) removes exactly that value:
 *       {@code avg = (qty × avg − qtyOut × costOut) / (qty − qtyOut)} while something remains,
 *       else the last average; never below zero;
 *   <li>every other movement changes the quantity and keeps the average, the last known one when
 *       the quantity reaches zero. An out movement at the average is the "out at a given cost"
 *       rule with the cost equal to the average, which leaves the average where it was.
 * </ul>
 * The shared engine has no cost arithmetic yet (it holds Money and Quantity only), so the scale
 * and rounding are written here once, as {@link #COST_SCALE} and {@link #ROUNDING}. Every result is
 * rounded to four decimals, as every average-cost movement is; a repack's output cost loses at most
 * half a unit of the fourth decimal per pack, which is accepted (Rs 0.05 per 1,000 packs).
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

    /**
     * The row after one movement of {@code qtyDelta} (signed) at {@code unitCost}: required for a
     * type that carries its own cost, optional for an out movement (null: at the average), ignored
     * otherwise.
     */
    static CostRow apply(CostRow row, MovementType type, BigDecimal qtyDelta, BigDecimal unitCost) {
        BigDecimal qty = row.qtyOnHand().add(qtyDelta).setScale(QTY_SCALE, ROUNDING);

        if (type.carriesItsOwnCost()) {
            if (row.qtyOnHand().signum() <= 0) {
                return new CostRow(qty, unitCost.setScale(COST_SCALE, ROUNDING));
            }
            BigDecimal value = row.qtyOnHand().multiply(row.avgCost()).add(qtyDelta.multiply(unitCost));
            BigDecimal avg = value.divide(row.qtyOnHand().add(qtyDelta), COST_SCALE, ROUNDING);
            return new CostRow(qty, avg);
        }
        if (isOutAtGivenCost(qtyDelta, unitCost) && qty.signum() > 0) {
            BigDecimal value = row.qtyOnHand().multiply(row.avgCost()).add(qtyDelta.multiply(unitCost));
            BigDecimal avg = value.divide(qty, COST_SCALE, ROUNDING);
            return new CostRow(qty, avg.signum() < 0 ? BigDecimal.ZERO.setScale(COST_SCALE) : avg);
        }
        return new CostRow(qty, row.avgCost());
    }

    /**
     * The cost a movement carries (25A section 6.1, "costFor"): the caller's for a type that
     * carries its own (an intake, a transfer in at its transfer out's cost, a sale reversal) and
     * for an out movement given a cost, else the entity average at that moment.
     */
    static BigDecimal costAtMovement(CostRow row, MovementType type, BigDecimal qtyDelta, BigDecimal unitCost) {
        return type.carriesItsOwnCost() || isOutAtGivenCost(qtyDelta, unitCost)
                ? unitCost.setScale(COST_SCALE, ROUNDING)
                : row.avgCost();
    }

    private static boolean isOutAtGivenCost(BigDecimal qtyDelta, BigDecimal unitCost) {
        return unitCost != null && qtyDelta.signum() < 0;
    }
}
