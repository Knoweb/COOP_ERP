package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;

/**
 * Command to evaluate near-expiry lots and slow-moving inventory at a location and apply
 * active promotional discount markdowns (PGL-M1-07).
 */
public record EvaluateExpirySlowMovingDiscounts(UUID locationId) {}
