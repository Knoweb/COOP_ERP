package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SubmitCount (25A section 6.3): what was counted, lot by lot. Every lot noted at the start is
 * counted or skipped with a reason; a lot found that the book did not know is a line of its own.
 */
public record SubmitCount(UUID taskId, List<Line> lines) {

    /**
     * @param countedQty what was counted, zero or more, in the SKU's base unit; null when skipped
     * @param skipReason why the lot was not counted; null when counted
     * @param countedAt  when the lot was counted (the form stamps each line; wave 2, M5-13): the
     *                   line is measured against the lot as it stood then; null for the lot at submit
     */
    public record Line(
            UUID batchId, LotCondition condition, BigDecimal countedQty, String skipReason, Instant countedAt) {

        /** A line with no moment of its own: measured against the lot at submit. */
        public Line(UUID batchId, LotCondition condition, BigDecimal countedQty, String skipReason) {
            this(batchId, condition, countedQty, skipReason, null);
        }
    }
}
