package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * DefineRecipe (25A section 6.3; doc 25 section 3.6): how much of the input item makes how much of
 * the output item, and the loss expected on the way.
 */
public record DefineRecipe(
        String name,
        UUID inputSkuId,
        BigDecimal inputQty,
        UUID outputSkuId,
        BigDecimal outputQty,
        BigDecimal expectedLossPct) {}
