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
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m9integration.api.RecordVehicleLocation;
import lk.coopfed.knoweb.m9integration.api.VehicleLocationRecorded;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles real-time GPS location updates from delivery vehicles and tracking devices (M3-06).
 */
@Service
@CommandHandler(permission = "int.delivery.track")
public class RecordVehicleLocationHandler implements Handles<RecordVehicleLocation, UUID> {

    public static final String AUDIT_LOCATION_RECORDED = "VEHICLE_LOCATION_RECORDED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    public RecordVehicleLocationHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID handle(RecordVehicleLocation command, ScopeContext scope) {
        if (command.vehicleId() == null || command.vehicleId().isBlank()) {
            throw new ProblemException("m9.delivery.vehicle_id_required");
        }
        if (command.deviceId() == null || command.deviceId().isBlank()) {
            throw new ProblemException("m9.delivery.device_id_required");
        }
        if (command.latitude() == null
                || command.latitude().compareTo(BigDecimal.valueOf(-90)) < 0
                || command.latitude().compareTo(BigDecimal.valueOf(90)) > 0
                || command.longitude() == null
                || command.longitude().compareTo(BigDecimal.valueOf(-180)) < 0
                || command.longitude().compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new ProblemException("m9.delivery.coordinates_invalid");
        }

        UUID ownerEntityId = scope.entityId();
        Instant now = clock.instant();
        Instant recordedAt = command.recordedAt() != null ? command.recordedAt() : now;

        UUID tripId = command.tripId();
        if (tripId != null) {
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM integration.delivery_trip WHERE trip_id = ? AND owner_entity_id = ?",
                    Integer.class,
                    tripId,
                    ownerEntityId);
            if (count == null || count == 0) {
                throw new ProblemException("m9.delivery.trip_not_found");
            }
            // Auto-advance PLANNED trip to IN_TRANSIT on first telemetry
            jdbc.update(
                    """
                    UPDATE integration.delivery_trip
                       SET trip_status = 'IN_TRANSIT',
                           started_at = coalesce(started_at, ?),
                           updated_at = ?
                     WHERE trip_id = ? AND trip_status = 'PLANNED'
                    """,
                    Timestamp.from(recordedAt),
                    Timestamp.from(now),
                    tripId);
        }

        UUID updateId = Ids.next();
        BigDecimal speedKmh = command.speedKmh() != null ? command.speedKmh() : BigDecimal.ZERO;
        String vehicleId = command.vehicleId().trim();
        String deviceId = command.deviceId().trim();

        jdbc.update(
                """
                INSERT INTO integration.vehicle_location_update
                    (update_id, owner_entity_id, vehicle_id, device_id, trip_id,
                     latitude, longitude, speed_kmh, heading_degrees, battery_percentage,
                     recorded_at, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                updateId,
                ownerEntityId,
                vehicleId,
                deviceId,
                tripId,
                command.latitude(),
                command.longitude(),
                speedKmh,
                command.headingDegrees(),
                command.batteryPercentage(),
                Timestamp.from(recordedAt),
                Timestamp.from(now));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("update_id", updateId.toString());
        after.put("vehicle_id", vehicleId);
        after.put("device_id", deviceId);
        after.put("latitude", command.latitude().toPlainString());
        after.put("longitude", command.longitude().toPlainString());
        after.put("speed_kmh", speedKmh.toPlainString());
        if (tripId != null) {
            after.put("trip_id", tripId.toString());
        }

        audit.record(AUDIT_LOCATION_RECORDED, Subject.of("vehicle_location_update", updateId), null, after, scope);

        events.publish(new VehicleLocationRecorded(
                updateId,
                ownerEntityId,
                vehicleId,
                deviceId,
                tripId,
                command.latitude(),
                command.longitude(),
                speedKmh,
                recordedAt));

        return updateId;
    }
}
