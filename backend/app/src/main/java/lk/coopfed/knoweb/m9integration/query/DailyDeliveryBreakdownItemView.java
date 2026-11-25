package lk.coopfed.knoweb.m9integration.query;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Daily breakdown item within month-end delivery reports (M3-06).
 */
public record DailyDeliveryBreakdownItemView(
        LocalDate date,
        int totalTrips,
        int totalDeliveries,
        int deliveredCount,
        int failedCount,
        BigDecimal totalDistanceKm) {}
