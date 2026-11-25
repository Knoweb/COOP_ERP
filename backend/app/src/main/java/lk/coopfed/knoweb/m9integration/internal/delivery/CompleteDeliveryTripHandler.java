package lk.coopfed.knoweb.m9integration.internal.delivery;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m9integration.api.CompleteDeliveryTrip;
import lk.coopfed.knoweb.m9integration.api.DeliveryTripCompleted;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles completing an end-of-day delivery trip with odometer readings and distance (M3-06).
 */
@Service
@CommandHandler(permission = "int.delivery.manage")
public class CompleteDeliveryTripHandler implements Handles<CompleteDeliveryTrip, Void> {

    public static final String AUDIT_TRIP_COMPLETED = "DELIVERY_TRIP_COMPLETED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    public CompleteDeliveryTripHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Void handle(CompleteDeliveryTrip command, ScopeContext scope) {
        if (command.tripId() == null) {
            throw new ProblemException("m9.delivery.trip_not_found");
        }

        UUID ownerEntityId = scope.entityId();
        Map<String, Object> existing = jdbc.query(
                "SELECT trip_id, trip_reference, vehicle_id, trip_status, start_odometer_km FROM integration.delivery_trip WHERE trip_id = ? AND owner_entity_id = ?",
                rs -> rs.next()
                        ? Map.of(
                                "trip_id", rs.getObject("trip_id"),
                                "trip_reference", rs.getString("trip_reference"),
                                "vehicle_id", rs.getString("vehicle_id"),
                                "trip_status", rs.getString("trip_status"),
                                "start_odometer_km", rs.getBigDecimal("start_odometer_km"))
                        : null,
                command.tripId(),
                ownerEntityId);

        if (existing == null) {
            throw new ProblemException("m9.delivery.trip_not_found");
        }
        String status = (String) existing.get("trip_status");
        if ("COMPLETED".equals(status) || "CANCELLED".equals(status)) {
            throw new ProblemException("m9.delivery.trip_completed");
        }

        BigDecimal startOdo = (BigDecimal) existing.get("start_odometer_km");
        BigDecimal endOdo = command.endOdometerKm();
        BigDecimal totalDist = command.totalDistanceKm();

        if (endOdo != null && startOdo != null && endOdo.compareTo(startOdo) < 0) {
            throw new ProblemException("m9.delivery.odometer_invalid");
        }

        if (totalDist == null) {
            if (endOdo != null && startOdo != null) {
                totalDist = endOdo.subtract(startOdo);
            } else {
                totalDist = BigDecimal.ZERO;
            }
        }

        Instant now = clock.instant();

        jdbc.update(
                """
                UPDATE integration.delivery_trip
                   SET trip_status = 'COMPLETED',
                       end_odometer_km = ?,
                       total_distance_km = ?,
                       completed_at = ?,
                       updated_at = ?
                 WHERE trip_id = ?
                """,
                endOdo,
                totalDist,
                Timestamp.from(now),
                Timestamp.from(now),
                command.tripId());

        Map<String, Object> before = Map.of("trip_status", status);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("trip_status", "COMPLETED");
        after.put("total_distance_km", totalDist.toPlainString());
        if (endOdo != null) {
            after.put("end_odometer_km", endOdo.toPlainString());
        }

        audit.record(AUDIT_TRIP_COMPLETED, Subject.of("delivery_trip", command.tripId()), before, after, scope);

        events.publish(new DeliveryTripCompleted(
                command.tripId(),
                ownerEntityId,
                (String) existing.get("trip_reference"),
                (String) existing.get("vehicle_id"),
                totalDist,
                now));

        return null;
    }
}
