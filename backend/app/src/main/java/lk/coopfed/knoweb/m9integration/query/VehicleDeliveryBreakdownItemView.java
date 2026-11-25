package lk.coopfed.knoweb.m9integration.query;

import java.math.BigDecimal;

/**
 * Per-vehicle delivery breakdown item within month-end delivery reports (M3-06).
 */
public record VehicleDeliveryBreakdownItemView(
        String vehicleId,
        int totalTrips,
        int totalDeliveries,
        int deliveredCount,
        int failedCount,
        BigDecimal totalDistanceKm) {}
