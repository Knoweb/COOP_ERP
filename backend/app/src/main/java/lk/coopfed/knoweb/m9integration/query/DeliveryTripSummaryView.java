package lk.coopfed.knoweb.m9integration.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Summary view of a delivery trip with drop metrics (M3-06).
 */
public record DeliveryTripSummaryView(
        UUID tripId,
        UUID ownerEntityId,
        String tripReference,
        String vehicleId,
        String driverCode,
        UUID originLocationId,
        UUID destinationLocationId,
        LocalDate tripDate,
        String tripStatus,
        BigDecimal totalDistanceKm,
        int totalDrops,
        int deliveredDrops,
        int failedDrops,
        Instant startedAt,
        Instant completedAt) {}
