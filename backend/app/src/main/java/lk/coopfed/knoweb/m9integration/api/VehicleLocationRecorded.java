package lk.coopfed.knoweb.m9integration.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Emitted when GPS coordinates are captured for a delivery vehicle (M3-06).
 */
public record VehicleLocationRecorded(
        UUID updateId,
        UUID ownerEntityId,
        String vehicleId,
        String deviceId,
        UUID tripId,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal speedKmh,
        Instant recordedAt)
        implements DomainEvent {

    public static final String TYPE = "integration.vehicle_location_recorded.v1";
}
