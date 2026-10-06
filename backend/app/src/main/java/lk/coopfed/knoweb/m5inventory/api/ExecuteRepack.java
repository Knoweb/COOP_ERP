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
 */
public record ExecuteRepack(
        UUID recipeId,
        UUID locationId,
        UUID inputBatchId,
        BigDecimal inputQty,
        BigDecimal actualOutputQty,
        BigDecimal printedMrp) {}
