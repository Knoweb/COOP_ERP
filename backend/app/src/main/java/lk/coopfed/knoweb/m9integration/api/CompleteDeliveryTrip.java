package lk.coopfed.knoweb.m9integration.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Concludes a delivery trip with ending odometer and total distance (M3-06).
 */
public record CompleteDeliveryTrip(UUID tripId, BigDecimal endOdometerKm, BigDecimal totalDistanceKm) {}
