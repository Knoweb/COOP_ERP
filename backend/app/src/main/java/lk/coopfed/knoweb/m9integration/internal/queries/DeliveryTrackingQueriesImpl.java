package lk.coopfed.knoweb.m9integration.internal.queries;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.query.DailyDeliveryBreakdownItemView;
import lk.coopfed.knoweb.m9integration.query.DailyDeliverySummaryView;
import lk.coopfed.knoweb.m9integration.query.DeliveryDropView;
import lk.coopfed.knoweb.m9integration.query.DeliveryTrackingQueries;
import lk.coopfed.knoweb.m9integration.query.DeliveryTripSummaryView;
import lk.coopfed.knoweb.m9integration.query.LiveVehicleLocationView;
import lk.coopfed.knoweb.m9integration.query.MonthlyDeliveryReportView;
import lk.coopfed.knoweb.m9integration.query.VehicleDeliveryBreakdownItemView;
import lk.coopfed.knoweb.m9integration.query.VehicleRoutePointView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of DeliveryTrackingQueries protected by PostgreSQL Row-Level Security (M3-06).
 */
@Service
@Transactional(readOnly = true)
class DeliveryTrackingQueriesImpl implements DeliveryTrackingQueries {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    DeliveryTrackingQueriesImpl(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<LiveVehicleLocationView> getLatestVehicleLocation(String vehicleId, ScopeContext scope) {
        if (vehicleId == null || vehicleId.isBlank()) {
            return Optional.empty();
        }
        List<LiveVehicleLocationView> list = jdbc.query(
                """
                SELECT vehicle_id, device_id, trip_id, latitude, longitude,
                       speed_kmh, heading_degrees, battery_percentage,
                       recorded_at, received_at
                  FROM integration.vehicle_location_update
                 WHERE vehicle_id = ?
                 ORDER BY recorded_at DESC
                 LIMIT 1
                """,
                (rs, rowNum) -> mapLiveLocation(rs),
                vehicleId.trim());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<LiveVehicleLocationView> listLiveVehicleLocations(ScopeContext scope) {
        return jdbc.query(
                """
                SELECT DISTINCT ON (vehicle_id)
                       vehicle_id, device_id, trip_id, latitude, longitude,
                       speed_kmh, heading_degrees, battery_percentage,
                       recorded_at, received_at
                  FROM integration.vehicle_location_update
                 ORDER BY vehicle_id, recorded_at DESC
                """,
                (rs, rowNum) -> mapLiveLocation(rs));
    }

    @Override
    public List<VehicleRoutePointView> getVehicleRouteHistory(String vehicleId, LocalDate date, ScopeContext scope) {
        if (vehicleId == null || vehicleId.isBlank() || date == null) {
            return List.of();
        }
        return jdbc.query(
                """
                SELECT recorded_at, latitude, longitude, speed_kmh, heading_degrees
                  FROM integration.vehicle_location_update
                 WHERE vehicle_id = ?
                   AND recorded_at >= ?::date
                   AND recorded_at < (?::date + interval '1 day')
                 ORDER BY recorded_at ASC
                """,
                (rs, rowNum) -> new VehicleRoutePointView(
                        toInstant(rs.getTimestamp("recorded_at")),
                        rs.getBigDecimal("latitude"),
                        rs.getBigDecimal("longitude"),
                        rs.getBigDecimal("speed_kmh"),
                        (Integer) rs.getObject("heading_degrees")),
                vehicleId.trim(),
                Date.valueOf(date),
                Date.valueOf(date));
    }

    @Override
    public Optional<DeliveryTripSummaryView> getDeliveryTrip(UUID tripId, ScopeContext scope) {
        if (tripId == null) {
            return Optional.empty();
        }
        List<DeliveryTripSummaryView> list = jdbc.query(
                """
                SELECT t.trip_id, t.owner_entity_id, t.trip_reference, t.vehicle_id,
                       t.driver_code, t.origin_location_id, t.destination_location_id,
                       t.trip_date, t.trip_status, t.total_distance_km,
                       t.started_at, t.completed_at,
                       coalesce(d.total_drops, 0) as total_drops,
                       coalesce(d.delivered_drops, 0) as delivered_drops,
                       coalesce(d.failed_drops, 0) as failed_drops
                  FROM integration.delivery_trip t
             LEFT JOIN (
                    SELECT trip_id,
                           count(*) as total_drops,
                           count(*) FILTER (WHERE status = 'DELIVERED') as delivered_drops,
                           count(*) FILTER (WHERE status = 'FAILED') as failed_drops
                      FROM integration.delivery_record
                     GROUP BY trip_id
                  ) d ON d.trip_id = t.trip_id
                 WHERE t.trip_id = ?
                """,
                (rs, rowNum) -> mapTripSummary(rs),
                tripId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<DeliveryDropView> listTripDrops(UUID tripId, ScopeContext scope) {
        if (tripId == null) {
            return List.of();
        }
        return jdbc.query(
                """
                SELECT delivery_id, trip_id, delivery_reference, destination_location_id,
                       recipient_ref, packages_count, weight_kg, status,
                       scheduled_at, delivered_at, failure_reason, notes
                  FROM integration.delivery_record
                 WHERE trip_id = ?
                 ORDER BY created_at ASC
                """,
                (rs, rowNum) -> new DeliveryDropView(
                        rs.getObject("delivery_id", UUID.class),
                        rs.getObject("trip_id", UUID.class),
                        rs.getString("delivery_reference"),
                        rs.getObject("destination_location_id", UUID.class),
                        rs.getString("recipient_ref"),
                        rs.getInt("packages_count"),
                        rs.getBigDecimal("weight_kg"),
                        rs.getString("status"),
                        toInstant(rs.getTimestamp("scheduled_at")),
                        toInstant(rs.getTimestamp("delivered_at")),
                        rs.getString("failure_reason"),
                        rs.getString("notes")),
                tripId);
    }

    @Override
    public Optional<DeliveryDropView> getDeliveryDrop(UUID deliveryId, ScopeContext scope) {
        if (deliveryId == null) {
            return Optional.empty();
        }
        List<DeliveryDropView> list = jdbc.query(
                """
                SELECT delivery_id, trip_id, delivery_reference, destination_location_id,
                       recipient_ref, packages_count, weight_kg, status,
                       scheduled_at, delivered_at, failure_reason, notes
                  FROM integration.delivery_record
                 WHERE delivery_id = ?
                """,
                (rs, rowNum) -> new DeliveryDropView(
                        rs.getObject("delivery_id", UUID.class),
                        rs.getObject("trip_id", UUID.class),
                        rs.getString("delivery_reference"),
                        rs.getObject("destination_location_id", UUID.class),
                        rs.getString("recipient_ref"),
                        rs.getInt("packages_count"),
                        rs.getBigDecimal("weight_kg"),
                        rs.getString("status"),
                        toInstant(rs.getTimestamp("scheduled_at")),
                        toInstant(rs.getTimestamp("delivered_at")),
                        rs.getString("failure_reason"),
                        rs.getString("notes")),
                deliveryId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public DailyDeliverySummaryView getDailyDeliverySummary(LocalDate date, UUID locationId, ScopeContext scope) {
        if (date == null) {
            date = LocalDate.now(clock);
        }

        String locationFilter = locationId != null ? " AND t.origin_location_id = ? " : "";
        Object[] args =
                locationId != null ? new Object[] {Date.valueOf(date), locationId} : new Object[] {Date.valueOf(date)};

        List<DeliveryTripSummaryView> trips = jdbc.query(
                """
                SELECT t.trip_id, t.owner_entity_id, t.trip_reference, t.vehicle_id,
                       t.driver_code, t.origin_location_id, t.destination_location_id,
                       t.trip_date, t.trip_status, t.total_distance_km,
                       t.started_at, t.completed_at,
                       coalesce(d.total_drops, 0) as total_drops,
                       coalesce(d.delivered_drops, 0) as delivered_drops,
                       coalesce(d.failed_drops, 0) as failed_drops
                  FROM integration.delivery_trip t
             LEFT JOIN (
                    SELECT trip_id,
                           count(*) as total_drops,
                           count(*) FILTER (WHERE status = 'DELIVERED') as delivered_drops,
                           count(*) FILTER (WHERE status = 'FAILED') as failed_drops
                      FROM integration.delivery_record
                     GROUP BY trip_id
                  ) d ON d.trip_id = t.trip_id
                 WHERE t.trip_date = ?
                """
                        + locationFilter
                        + " ORDER BY t.created_at ASC",
                (rs, rowNum) -> mapTripSummary(rs),
                args);

        int totalTrips = trips.size();
        int completedTrips = (int)
                trips.stream().filter(t -> "COMPLETED".equals(t.tripStatus())).count();
        int totalDeliveries =
                trips.stream().mapToInt(DeliveryTripSummaryView::totalDrops).sum();
        int deliveredCount =
                trips.stream().mapToInt(DeliveryTripSummaryView::deliveredDrops).sum();
        int failedCount =
                trips.stream().mapToInt(DeliveryTripSummaryView::failedDrops).sum();
        int pendingCount = totalDeliveries - deliveredCount - failedCount;

        BigDecimal completionRate = totalDeliveries > 0
                ? BigDecimal.valueOf(deliveredCount)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalDeliveries), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal totalDistance =
                trips.stream().map(DeliveryTripSummaryView::totalDistanceKm).reduce(BigDecimal.ZERO, BigDecimal::add);

        int activeVehiclesCount = (int) trips.stream()
                .map(DeliveryTripSummaryView::vehicleId)
                .distinct()
                .count();

        return new DailyDeliverySummaryView(
                date,
                totalTrips,
                completedTrips,
                totalDeliveries,
                deliveredCount,
                failedCount,
                pendingCount,
                completionRate,
                totalDistance,
                activeVehiclesCount,
                trips);
    }

    @Override
    public MonthlyDeliveryReportView getMonthlyDeliveryReport(String periodMonth, UUID locationId, ScopeContext scope) {
        if (periodMonth == null || !periodMonth.matches("^\\d{4}-(0[1-9]|1[0-2])$")) {
            throw new ProblemException("m9.delivery.period_invalid");
        }

        YearMonth ym;
        try {
            ym = YearMonth.parse(periodMonth);
        } catch (DateTimeParseException e) {
            throw new ProblemException("m9.delivery.period_invalid");
        }

        LocalDate startDate = ym.atDay(1);
        LocalDate endDate = ym.atEndOfMonth();

        String locationFilter = locationId != null ? " AND t.origin_location_id = ? " : "";
        Object[] args = locationId != null
                ? new Object[] {Date.valueOf(startDate), Date.valueOf(endDate), locationId}
                : new Object[] {Date.valueOf(startDate), Date.valueOf(endDate)};

        List<DeliveryTripSummaryView> trips = jdbc.query(
                """
                SELECT t.trip_id, t.owner_entity_id, t.trip_reference, t.vehicle_id,
                       t.driver_code, t.origin_location_id, t.destination_location_id,
                       t.trip_date, t.trip_status, t.total_distance_km,
                       t.started_at, t.completed_at,
                       coalesce(d.total_drops, 0) as total_drops,
                       coalesce(d.delivered_drops, 0) as delivered_drops,
                       coalesce(d.failed_drops, 0) as failed_drops
                  FROM integration.delivery_trip t
             LEFT JOIN (
                    SELECT trip_id,
                           count(*) as total_drops,
                           count(*) FILTER (WHERE status = 'DELIVERED') as delivered_drops,
                           count(*) FILTER (WHERE status = 'FAILED') as failed_drops
                      FROM integration.delivery_record
                     GROUP BY trip_id
                  ) d ON d.trip_id = t.trip_id
                 WHERE t.trip_date >= ? AND t.trip_date <= ?
                """
                        + locationFilter
                        + " ORDER BY t.trip_date ASC, t.created_at ASC",
                (rs, rowNum) -> mapTripSummary(rs),
                args);

        int totalTrips = trips.size();
        int completedTrips = (int)
                trips.stream().filter(t -> "COMPLETED".equals(t.tripStatus())).count();
        int totalDeliveries =
                trips.stream().mapToInt(DeliveryTripSummaryView::totalDrops).sum();
        int deliveredCount =
                trips.stream().mapToInt(DeliveryTripSummaryView::deliveredDrops).sum();
        int failedCount =
                trips.stream().mapToInt(DeliveryTripSummaryView::failedDrops).sum();

        BigDecimal successRate = totalDeliveries > 0
                ? BigDecimal.valueOf(deliveredCount)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalDeliveries), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal totalDistance =
                trips.stream().map(DeliveryTripSummaryView::totalDistanceKm).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Daily breakdown aggregation
        Map<LocalDate, List<DeliveryTripSummaryView>> byDate = new LinkedHashMap<>();
        for (DeliveryTripSummaryView t : trips) {
            byDate.computeIfAbsent(t.tripDate(), k -> new ArrayList<>()).add(t);
        }

        List<DailyDeliveryBreakdownItemView> dailyBreakdown = new ArrayList<>();
        for (Map.Entry<LocalDate, List<DeliveryTripSummaryView>> entry : byDate.entrySet()) {
            List<DeliveryTripSummaryView> dayTrips = entry.getValue();
            int dayDeliveries = dayTrips.stream()
                    .mapToInt(DeliveryTripSummaryView::totalDrops)
                    .sum();
            int dayDelivered = dayTrips.stream()
                    .mapToInt(DeliveryTripSummaryView::deliveredDrops)
                    .sum();
            int dayFailed = dayTrips.stream()
                    .mapToInt(DeliveryTripSummaryView::failedDrops)
                    .sum();
            BigDecimal dayDist = dayTrips.stream()
                    .map(DeliveryTripSummaryView::totalDistanceKm)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            dailyBreakdown.add(new DailyDeliveryBreakdownItemView(
                    entry.getKey(), dayTrips.size(), dayDeliveries, dayDelivered, dayFailed, dayDist));
        }

        // Vehicle breakdown aggregation
        Map<String, List<DeliveryTripSummaryView>> byVehicle = new LinkedHashMap<>();
        for (DeliveryTripSummaryView t : trips) {
            byVehicle.computeIfAbsent(t.vehicleId(), k -> new ArrayList<>()).add(t);
        }

        List<VehicleDeliveryBreakdownItemView> vehicleBreakdown = new ArrayList<>();
        for (Map.Entry<String, List<DeliveryTripSummaryView>> entry : byVehicle.entrySet()) {
            List<DeliveryTripSummaryView> vehTrips = entry.getValue();
            int vehDeliveries = vehTrips.stream()
                    .mapToInt(DeliveryTripSummaryView::totalDrops)
                    .sum();
            int vehDelivered = vehTrips.stream()
                    .mapToInt(DeliveryTripSummaryView::deliveredDrops)
                    .sum();
            int vehFailed = vehTrips.stream()
                    .mapToInt(DeliveryTripSummaryView::failedDrops)
                    .sum();
            BigDecimal vehDist = vehTrips.stream()
                    .map(DeliveryTripSummaryView::totalDistanceKm)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            vehicleBreakdown.add(new VehicleDeliveryBreakdownItemView(
                    entry.getKey(), vehTrips.size(), vehDeliveries, vehDelivered, vehFailed, vehDist));
        }

        return new MonthlyDeliveryReportView(
                periodMonth,
                totalTrips,
                completedTrips,
                totalDeliveries,
                deliveredCount,
                failedCount,
                successRate,
                totalDistance,
                dailyBreakdown,
                vehicleBreakdown);
    }

    private LiveVehicleLocationView mapLiveLocation(ResultSet rs) throws SQLException {
        return new LiveVehicleLocationView(
                rs.getString("vehicle_id"),
                rs.getString("device_id"),
                rs.getObject("trip_id", UUID.class),
                rs.getBigDecimal("latitude"),
                rs.getBigDecimal("longitude"),
                rs.getBigDecimal("speed_kmh"),
                (Integer) rs.getObject("heading_degrees"),
                (Integer) rs.getObject("battery_percentage"),
                toInstant(rs.getTimestamp("recorded_at")),
                toInstant(rs.getTimestamp("received_at")));
    }

    private DeliveryTripSummaryView mapTripSummary(ResultSet rs) throws SQLException {
        return new DeliveryTripSummaryView(
                rs.getObject("trip_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getString("trip_reference"),
                rs.getString("vehicle_id"),
                rs.getString("driver_code"),
                rs.getObject("origin_location_id", UUID.class),
                rs.getObject("destination_location_id", UUID.class),
                rs.getDate("trip_date").toLocalDate(),
                rs.getString("trip_status"),
                rs.getBigDecimal("total_distance_km"),
                rs.getInt("total_drops"),
                rs.getInt("delivered_drops"),
                rs.getInt("failed_drops"),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("completed_at")));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
