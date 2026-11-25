package lk.coopfed.knoweb.m3pricing.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Result returned by EvaluateExpirySlowMovingDiscountsHandler (PGL-M1-07).
 */
public record ExpirySlowMovingEvaluationResult(
        UUID locationId,
        int totalEvaluated,
        int markdownsActive,
        int nearExpiryCount,
        int slowMovingCount,
        Instant evaluatedAt) {}
