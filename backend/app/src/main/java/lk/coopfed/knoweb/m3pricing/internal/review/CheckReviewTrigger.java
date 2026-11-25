package lk.coopfed.knoweb.m3pricing.internal.review;

import java.util.UUID;

/**
 * The review job has checked a trigger against every list in force and raised
 * {@code reviewsRaised} reviews. Internal to M3: only {@link PricingReviewJob} sends it, in the
 * OWN scope of the trigger's owner.
 */
record CheckReviewTrigger(UUID triggerId, int reviewsRaised) {}
