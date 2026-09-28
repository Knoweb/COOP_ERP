package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.util.UUID;

/** A repack recipe (doc 25 section 3.6): ACTIVE or RETIRED. */
public record RecipeView(
        UUID recipeId,
        String name,
        UUID inputSkuId,
        BigDecimal inputQty,
        UUID outputSkuId,
        BigDecimal outputQty,
        BigDecimal expectedLossPct,
        String status) {}
