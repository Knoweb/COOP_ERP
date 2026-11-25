package lk.coopfed.knoweb.m9integration.internal.delivery;

import java.math.BigDecimal;
import java.sql.Date;
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
import lk.coopfed.knoweb.m9integration.api.CreateDeliveryTrip;
import lk.coopfed.knoweb.m9integration.api.DeliveryTripCreated;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles planning and initiation of a delivery trip for an entity (M3-06).
 */
@Service
@CommandHandler(permission = "int.delivery.manage")
public class CreateDeliveryTripHandler implements Handles<CreateDeliveryTrip, UUID> {

    public static final String AUDIT_TRIP_CREATED = "DELIVERY_TRIP_CREATED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    public CreateDeliveryTripHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID handle(CreateDeliveryTrip command, ScopeContext scope) {
        if (command.tripReference() == null || command.tripReference().isBlank()) {
            throw new ProblemException("m9.delivery.trip_reference_required");
        }
        if (command.vehicleId() == null || command.vehicleId().isBlank()) {
            throw new ProblemException("m9.delivery.vehicle_id_required");
        }
        if (command.originLocationId() == null) {
            throw new ProblemException("m9.delivery.origin_location_required");
        }
        if (command.tripDate() == null) {
            throw new ProblemException("m9.delivery.trip_date_required");
        }

        UUID ownerEntityId = scope.entityId();
        String tripReference = command.tripReference().trim();

        Integer duplicate = jdbc.queryForObject(
                "SELECT count(*) FROM integration.delivery_trip WHERE owner_entity_id = ? AND trip_reference = ?",
                Integer.class,
                ownerEntityId,
                tripReference);
        if (duplicate != null && duplicate > 0) {
            throw new ProblemException("m9.delivery.trip_reference_duplicate");
        }

        UUID tripId = Ids.next();
        Instant now = clock.instant();
        String vehicleId = command.vehicleId().trim();
        String driverCode = command.driverCode() != null ? command.driverCode().trim() : null;
        BigDecimal startOdometer = command.startOdometerKm() != null ? command.startOdometerKm() : BigDecimal.ZERO;

        jdbc.update(
                """
                INSERT INTO integration.delivery_trip
                    (trip_id, owner_entity_id, trip_reference, vehicle_id,
                     driver_user_id, driver_code, origin_location_id, destination_location_id,
                     trip_date, trip_status, start_odometer_km, total_distance_km,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PLANNED', ?, 0.00, ?, ?)
                """,
                tripId,
                ownerEntityId,
                tripReference,
                vehicleId,
                command.driverUserId(),
                driverCode,
                command.originLocationId(),
                command.destinationLocationId(),
                Date.valueOf(command.tripDate()),
                startOdometer,
                Timestamp.from(now),
                Timestamp.from(now));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("trip_id", tripId.toString());
        after.put("trip_reference", tripReference);
        after.put("vehicle_id", vehicleId);
        if (driverCode != null) {
            after.put("driver_code", driverCode);
        }
        after.put("origin_location_id", command.originLocationId().toString());
        after.put("trip_date", command.tripDate().toString());
        after.put("trip_status", "PLANNED");

        audit.record(AUDIT_TRIP_CREATED, Subject.of("delivery_trip", tripId), null, after, scope);

        events.publish(new DeliveryTripCreated(
                tripId, ownerEntityId, tripReference, vehicleId, command.originLocationId(), command.tripDate()));

        return tripId;
    }
}
