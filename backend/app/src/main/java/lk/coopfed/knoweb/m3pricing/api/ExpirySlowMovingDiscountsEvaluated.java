package lk.coopfed.knoweb.m3pricing.api;

import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Domain event published when expiry and slow-moving discount evaluation completes for a location (PGL-M1-07).
 */
public record ExpirySlowMovingDiscountsEvaluated(
        UUID evaluationId,
        UUID ownerEntityId,
        UUID locationId,
        int totalEvaluated,
        int markdownsActive,
        int nearExpiryCount,
        int slowMovingCount,
        Instant evaluatedAt)
        implements DomainEvent {

    public static final String TYPE = "pricing.expiry_slow_moving_discounts_evaluated.v1";
}
