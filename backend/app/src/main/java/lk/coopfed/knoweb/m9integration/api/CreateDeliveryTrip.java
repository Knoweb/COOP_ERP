package lk.coopfed.knoweb.m9integration.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Plans and initiates a delivery trip for a vehicle and driver (M3-06).
 */
public record CreateDeliveryTrip(
        String tripReference,
        String vehicleId,
        UUID driverUserId,
        String driverCode,
        UUID originLocationId,
        UUID destinationLocationId,
        LocalDate tripDate,
        BigDecimal startOdometerKm) {}
