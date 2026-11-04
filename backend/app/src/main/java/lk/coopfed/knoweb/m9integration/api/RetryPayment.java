package lk.coopfed.knoweb.m9integration.api;

import java.util.UUID;

/**
 * Command to retry payment processing with a provider (M3-07).
 */
public record RetryPayment(UUID paymentId, String provider) {}
