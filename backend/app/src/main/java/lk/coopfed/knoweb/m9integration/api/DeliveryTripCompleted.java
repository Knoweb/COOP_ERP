package lk.coopfed.knoweb.m9integration.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Emitted when a delivery trip is completed (M3-06).
 */
public record DeliveryTripCompleted(
        UUID tripId,
        UUID ownerEntityId,
        String tripReference,
        String vehicleId,
        BigDecimal totalDistanceKm,
        Instant completedAt)
        implements DomainEvent {

    public static final String TYPE = "integration.delivery_trip_completed.v1";
}
