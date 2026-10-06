package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A count and its adjustment (25A section 6.3). Status SCHEDULED, COUNTING, VARIANCE_REVIEW or
 * CLOSED; a CLOSED count's outcome is POSTED (every variance within tolerance, posted at submit),
 * APPROVED or REJECTED (the variances beyond tolerance).
 *
 * @param expectation the lots in scope at the start, with what the book said then
 * @param lines       what was counted, from the submit on; empty before
 */
public record CountView(
        UUID taskId,
        UUID locationId,
        String scopeKind,
        List<UUID> skuIds,
        LocalDate scheduledFor,
        String status,
        String outcome,
        UUID scheduledBy,
        Instant scheduledAt,
        UUID startedBy,
        Instant startedAt,
        UUID submittedBy,
        Instant submittedAt,
        BigDecimal reviewValue,
        Integer reviewBand,
        UUID reviewedBy,
        Instant reviewedAt,
        String reviewReason,
        List<Expected> expectation,
        List<Line> lines) {

    public record Expected(UUID batchId, UUID skuId, String condition, BigDecimal expectedQty) {}

    /**
     * @param expectedQty     the book at submit: the expectation moved by what was posted since the start
     * @param countedQty      null when skipped
     * @param varianceQty     counted minus expected; zero when skipped
     * @param withinTolerance posted at submit without approval
     */
    public record Line(
            int lineNo,
            UUID batchId,
            UUID skuId,
            String condition,
            BigDecimal expectedQty,
            BigDecimal countedQty,
            String skipReason,
            BigDecimal varianceQty,
            BigDecimal unitCost,
            BigDecimal varianceValue,
            boolean withinTolerance) {}
}
