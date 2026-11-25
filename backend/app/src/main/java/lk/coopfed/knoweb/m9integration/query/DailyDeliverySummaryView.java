package lk.coopfed.knoweb.m9integration.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * End-of-day summary of deliveries, completion rates, and trips (M3-06).
 */
public record DailyDeliverySummaryView(
        LocalDate date,
        int totalTrips,
        int completedTrips,
        int totalDeliveries,
        int deliveredCount,
        int failedCount,
        int pendingCount,
        BigDecimal completionRatePercent,
        BigDecimal totalDistanceKm,
        int activeVehiclesCount,
        List<DeliveryTripSummaryView> trips) {}
