package lk.coopfed.knoweb.m5inventory.internal.reorder;

/**
 * Result of evaluating low stock thresholds (PGL-M2-01).
 *
 * @param evaluatedCount number of reorder controls evaluated
 * @param alertsTriggeredCount number of fresh low-stock alerts emitted
 */
public record LowStockEvaluationResult(int evaluatedCount, int alertsTriggeredCount) {}
