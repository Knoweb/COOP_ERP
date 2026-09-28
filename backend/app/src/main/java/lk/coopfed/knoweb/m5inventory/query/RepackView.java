package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A repack (doc 25 section 3.6, flow 6.5): EXECUTED, or REVERSED once its reversal is written.
 *
 * @param varianceQty    expected minus actual output: the yield loss beyond the recipe's, reported
 *                       apart from shrinkage
 * @param outputUnitCost the consumed value divided by the actual output
 */
public record RepackView(
        UUID repackId,
        UUID locationId,
        UUID recipeId,
        UUID inputBatchId,
        UUID inputSkuId,
        BigDecimal inputQty,
        BigDecimal inputUnitCost,
        UUID outputSkuId,
        UUID outputBatchId,
        BigDecimal expectedOutputQty,
        BigDecimal actualOutputQty,
        BigDecimal varianceQty,
        BigDecimal outputUnitCost,
        String status,
        UUID executedBy,
        Instant executedAt,
        String reversalReason,
        Instant reversedAt) {}
