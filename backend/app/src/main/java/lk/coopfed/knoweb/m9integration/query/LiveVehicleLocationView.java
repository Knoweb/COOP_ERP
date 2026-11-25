package lk.coopfed.knoweb.m9integration.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Real-time latest GPS position of a delivery vehicle (M3-06).
 */
public record LiveVehicleLocationView(
        String vehicleId,
        String deviceId,
        UUID tripId,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal speedKmh,
        Integer headingDegrees,
        Integer batteryPercentage,
        Instant recordedAt,
        Instant receivedAt) {}
