package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/**
 * Command to evaluate near-expiry and slow-moving stock alerts for a location (PGL-M2-07).
 */
public record EvaluateExpiryAlerts(UUID locationId) {}
