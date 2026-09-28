package lk.coopfed.knoweb.m3pricing.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What SetLines answers (23A section 5: "per-line outcomes"): one outcome per line, in the
 * order given. The lines are stored only when every line is accepted ({@code saved}); a refused
 * line names its reason, a message id. A line may be accepted and still flagged for review
 * (doc 23 section 3.4: a trade price above the SKU's lowest printed MRP, doc 10 A-04).
 */
public record SetLinesResult(boolean saved, List<Outcome> outcomes) {

    /**
     * One line's outcome. The ceiling fields (M3-06; 23A section 5: "ceiling: {kind, value, ref}")
     * name the binding ceiling of a RETAIL or ADVISORY line, whether the line is under it or
     * refused for being above it, and the MRP a TRADE line is reviewed against; null when none.
     *
     * @param ceilingKind  CONTROL_PRICE or MRP
     * @param ceilingValue the ceiling, tax-inclusive
     * @param ceilingRef   the gazette reference of a control price, the batch number of an MRP
     */
    public record Outcome(
            UUID skuId,
            String uomCode,
            BigDecimal tierFromQty,
            boolean ok,
            String reason,
            String review,
            String ceilingKind,
            BigDecimal ceilingValue,
            String ceilingRef) {

        /** An outcome with no ceiling (M3-04's form). */
        public Outcome(UUID skuId, String uomCode, BigDecimal tierFromQty, boolean ok, String reason, String review) {
            this(skuId, uomCode, tierFromQty, ok, reason, review, null, null, null);
        }
    }
}
