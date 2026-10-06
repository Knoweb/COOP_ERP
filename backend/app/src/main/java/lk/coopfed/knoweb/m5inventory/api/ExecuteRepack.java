package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * ExecuteRepack (25A section 6.3; doc 25 flow 6.5): a quantity of one GOOD lot of the recipe's
 * input is consumed and the packs actually made are produced as a new batch of the output item.
 *
 * @param inputQty        what is taken from the input lot, in the input item's base unit
 * @param actualOutputQty what was actually made, in the output item's base unit
 * @param printedMrp      the MRP on the label, when the output item carries one; otherwise null
 * @param varianceReason  why fewer packs were made than the recipe expects; required when the
 *                        shortfall is beyond {@code inventory.repack_yield_tolerance_pct} (wave 2,
 *                        M5-18), otherwise null
 */
public record ExecuteRepack(
        UUID recipeId,
        UUID locationId,
        UUID inputBatchId,
        BigDecimal inputQty,
        BigDecimal actualOutputQty,
        BigDecimal printedMrp,
        String varianceReason) {

    /** A repack with no reason for its yield given. */
    public ExecuteRepack(
            UUID recipeId,
            UUID locationId,
            UUID inputBatchId,
            BigDecimal inputQty,
            BigDecimal actualOutputQty,
            BigDecimal printedMrp) {
        this(recipeId, locationId, inputBatchId, inputQty, actualOutputQty, printedMrp, null);
    }
}
