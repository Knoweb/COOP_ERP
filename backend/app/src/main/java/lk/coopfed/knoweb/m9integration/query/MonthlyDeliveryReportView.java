package lk.coopfed.knoweb.m9integration.query;

import java.math.BigDecimal;
import java.util.List;

/**
 * Month-end routine delivery report reconciling trips, drops, and vehicles (M3-06).
 */
public record MonthlyDeliveryReportView(
        String periodMonth,
        int totalTrips,
        int completedTrips,
        int totalDeliveries,
        int deliveredCount,
        int failedCount,
        BigDecimal deliverySuccessRate,
        BigDecimal totalDistanceKm,
        List<DailyDeliveryBreakdownItemView> dailyBreakdown,
        List<VehicleDeliveryBreakdownItemView> vehicleBreakdown) {}
