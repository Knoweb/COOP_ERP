package lk.coopfed.knoweb.m9integration.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Captures real-time GPS coordinates and telemetry for a delivery vehicle or mobile device (M3-06).
 */
public record RecordVehicleLocation(
        String vehicleId,
        String deviceId,
        UUID tripId,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal speedKmh,
        Integer headingDegrees,
        Integer batteryPercentage,
        Instant recordedAt) {}
