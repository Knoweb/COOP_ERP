package lk.coopfed.knoweb.m3pricing.internal.review;

import java.util.Map;
import java.util.UUID;

/**
 * One pricing review of a list owner, decided by {@link PricingReviewJob}: the kind (a REVIEW
 * code of {@link PricingReviews}), the SKU, the replay key ({@link PricingReviews#REVIEW_KEY}),
 * the reference the event carries (batch number, gazette reference) and the details of the audit
 * record. Internal to M3: only the job sends it, in the OWN scope of the list's owner.
 */
record RaiseReview(String kind, UUID skuId, String reviewKey, String reference, Map<String, Object> details) {}
