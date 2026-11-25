package lk.coopfed.knoweb.m9integration.api;

import java.util.UUID;

/**
 * Updates the execution status of a delivery drop (M3-06).
 */
public record UpdateDeliveryStatus(UUID deliveryId, String status, String failureReason, String notes) {}
