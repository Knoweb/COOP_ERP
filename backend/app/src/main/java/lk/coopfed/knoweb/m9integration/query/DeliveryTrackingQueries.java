package lk.coopfed.knoweb.m9integration.query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * Published query seam for delivery tracking, GPS routes, and delivery reporting (M3-06).
 */
public interface DeliveryTrackingQueries {

    /**
     * Retrieves the latest GPS fix for a specific vehicle in the caller's scope.
     */
    Optional<LiveVehicleLocationView> getLatestVehicleLocation(String vehicleId, ScopeContext scope);

    /**
     * Lists the latest GPS location for all active vehicles of the entity.
     */
    List<LiveVehicleLocationView> listLiveVehicleLocations(ScopeContext scope);

    /**
     * Retrieves the chronological route GPS history for a vehicle on a specific date.
     */
    List<VehicleRoutePointView> getVehicleRouteHistory(String vehicleId, LocalDate date, ScopeContext scope);

    /**
     * Retrieves a delivery trip by ID with drop metrics.
     */
    Optional<DeliveryTripSummaryView> getDeliveryTrip(UUID tripId, ScopeContext scope);

    /**
     * Lists all drops/stops scheduled on a delivery trip.
     */
    List<DeliveryDropView> listTripDrops(UUID tripId, ScopeContext scope);

    /**
     * Retrieves a single delivery drop by ID.
     */
    Optional<DeliveryDropView> getDeliveryDrop(UUID deliveryId, ScopeContext scope);

    /**
     * Generates an end-of-day delivery summary report for a given date.
     */
    DailyDeliverySummaryView getDailyDeliverySummary(LocalDate date, UUID locationId, ScopeContext scope);

    /**
     * Generates a month-end routine delivery report reconciling all trips and drops for the month.
     */
    MonthlyDeliveryReportView getMonthlyDeliveryReport(String periodMonth, UUID locationId, ScopeContext scope);
}
