package lk.coopfed.knoweb.m3pricing.internal.review;

import java.util.Set;

/**
 * The pricing reviews of doc 23 section 4.3 (23A section 7.1): REVIEW audit types of M3's own
 * (seed/m3pricing/audit-event-types.yaml), read back through the kernel's AuditReviewReader with
 * these codes (CR-19A-14: the kernel lists no module's codes).
 */
public final class PricingReviews {

    public static final String BATCH_MRP_BELOW_LIST = "BATCH_MRP_BELOW_LIST";
    public static final String TRADE_ABOVE_MRP = "TRADE_ABOVE_MRP";
    public static final String LIST_ABOVE_CEILING_AT_GAZETTE = "LIST_ABOVE_CEILING_AT_GAZETTE";

    public static final Set<String> KINDS =
            Set.of(BATCH_MRP_BELOW_LIST, TRADE_ABOVE_MRP, LIST_ABOVE_CEILING_AT_GAZETTE);

    /**
     * The after-state key that makes a review raised once (a replay of the job, or of the event,
     * finds it): the source (batch or control price) and the list kind, so a Federation with an
     * ADVISORY and a TRADE list above one gazette gets two reviews, not one (M3R-05).
     */
    public static final String REVIEW_KEY = "reviewKey";

    private PricingReviews() {}
}
