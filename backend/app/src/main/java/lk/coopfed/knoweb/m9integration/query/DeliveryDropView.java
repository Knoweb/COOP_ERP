package lk.coopfed.knoweb.m9integration.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Individual package drop / delivery point view (M3-06).
 */
public record DeliveryDropView(
        UUID deliveryId,
        UUID tripId,
        String deliveryReference,
        UUID destinationLocationId,
        String recipientRef,
        int packagesCount,
        BigDecimal weightKg,
        String status,
        Instant scheduledAt,
        Instant deliveredAt,
        String failureReason,
        String notes) {}
