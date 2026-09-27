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

    public record Outcome(
            UUID skuId, String uomCode, BigDecimal tierFromQty, boolean ok, String reason, String review) {}
}
