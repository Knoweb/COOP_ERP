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
import lk.coopfed.knoweb.m9integration.api.DeliveryRecorded;
import lk.coopfed.knoweb.m9integration.api.RecordDeliveryDrop;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles recording package drops on planned delivery trips (M3-06).
 */
@Service
@CommandHandler(permission = "int.delivery.manage")
public class RecordDeliveryDropHandler implements Handles<RecordDeliveryDrop, UUID> {

    public static final String AUDIT_DELIVERY_RECORDED = "DELIVERY_RECORDED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    public RecordDeliveryDropHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID handle(RecordDeliveryDrop command, ScopeContext scope) {
        if (command.tripId() == null) {
            throw new ProblemException("m9.delivery.trip_not_found");
        }
        if (command.deliveryReference() == null || command.deliveryReference().isBlank()) {
            throw new ProblemException("m9.delivery.delivery_reference_required");
        }
        if (command.destinationLocationId() == null) {
            throw new ProblemException("m9.delivery.destination_location_required");
        }

        UUID ownerEntityId = scope.entityId();
        String tripStatus = jdbc.query(
                "SELECT trip_status FROM integration.delivery_trip WHERE trip_id = ? AND owner_entity_id = ?",
                rs -> rs.next() ? rs.getString("trip_status") : null,
                command.tripId(),
                ownerEntityId);
        if (tripStatus == null) {
            throw new ProblemException("m9.delivery.trip_not_found");
        }
        if ("COMPLETED".equals(tripStatus) || "CANCELLED".equals(tripStatus)) {
            throw new ProblemException("m9.delivery.trip_completed");
        }

        UUID deliveryId = Ids.next();
        Instant now = clock.instant();
        String deliveryRef = command.deliveryReference().trim();
        int packages = command.packagesCount() != null && command.packagesCount() > 0 ? command.packagesCount() : 1;
        BigDecimal weight = command.weightKg() != null ? command.weightKg() : BigDecimal.ZERO;

        jdbc.update(
                """
                INSERT INTO integration.delivery_record
                    (delivery_id, owner_entity_id, trip_id, delivery_reference,
                     destination_location_id, recipient_ref, packages_count, weight_kg,
                     status, scheduled_at, notes, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
                """,
                deliveryId,
                ownerEntityId,
                command.tripId(),
                deliveryRef,
                command.destinationLocationId(),
                command.recipientRef(),
                packages,
                weight,
                command.scheduledAt() != null ? Timestamp.from(command.scheduledAt()) : null,
                command.notes(),
                Timestamp.from(now),
                Timestamp.from(now));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("delivery_id", deliveryId.toString());
        after.put("trip_id", command.tripId().toString());
        after.put("delivery_reference", deliveryRef);
        after.put("destination_location_id", command.destinationLocationId().toString());
        after.put("packages_count", packages);
        after.put("status", "PENDING");

        audit.record(AUDIT_DELIVERY_RECORDED, Subject.of("delivery_record", deliveryId), null, after, scope);

        events.publish(new DeliveryRecorded(
                deliveryId, ownerEntityId, command.tripId(), deliveryRef, command.destinationLocationId()));

        return deliveryId;
    }
}
