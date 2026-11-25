package lk.coopfed.knoweb.m9integration.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Schedules a package delivery drop on a delivery trip (M3-06).
 */
public record RecordDeliveryDrop(
        UUID tripId,
        String deliveryReference,
        UUID destinationLocationId,
        String recipientRef,
        Integer packagesCount,
        BigDecimal weightKg,
        Instant scheduledAt,
        String notes) {}
