package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * pricing.review_check_completed.v1: the review job checked a recorded batch MRP or control
 * price against every list in force and raised {@code reviewsRaised} pricing reviews (each its
 * own pricing.review_raised.v1). Bookkeeping of the job (CR-19A-14); no consumer yet.
 */
public record ReviewCheckCompleted(UUID triggerId, UUID ownerEntityId, int reviewsRaised) implements DomainEvent {

    public static final String TYPE = "pricing.review_check_completed.v1";
}
