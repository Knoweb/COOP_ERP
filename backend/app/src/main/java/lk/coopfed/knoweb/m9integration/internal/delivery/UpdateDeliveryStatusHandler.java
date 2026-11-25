package lk.coopfed.knoweb.m9integration.internal.delivery;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m9integration.api.DeliveryStatusUpdated;
import lk.coopfed.knoweb.m9integration.api.UpdateDeliveryStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles status updates for delivery drops (DELIVERED, FAILED, CANCELLED) (M3-06).
 */
@Service
@CommandHandler(permission = "int.delivery.manage")
public class UpdateDeliveryStatusHandler implements Handles<UpdateDeliveryStatus, Void> {

    public static final String AUDIT_STATUS_UPDATED = "DELIVERY_STATUS_UPDATED";
    private static final Set<String> ALLOWED_STATUSES = Set.of("DELIVERED", "FAILED", "CANCELLED", "IN_TRANSIT");

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    public UpdateDeliveryStatusHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Void handle(UpdateDeliveryStatus command, ScopeContext scope) {
        if (command.deliveryId() == null) {
            throw new ProblemException("m9.delivery.delivery_not_found");
        }
        String newStatus = command.status() != null ? command.status().trim().toUpperCase() : "";
        if (!ALLOWED_STATUSES.contains(newStatus)) {
            throw new ProblemException("m9.delivery.status_invalid");
        }
        if ("FAILED".equals(newStatus)
                && (command.failureReason() == null || command.failureReason().isBlank())) {
            throw new ProblemException("m9.delivery.failure_reason_required");
        }

        UUID ownerEntityId = scope.entityId();
        Map<String, Object> existing = jdbc.query(
                "SELECT delivery_id, trip_id, delivery_reference, status FROM integration.delivery_record WHERE delivery_id = ? AND owner_entity_id = ?",
                rs -> rs.next()
                        ? Map.of(
                                "delivery_id", rs.getObject("delivery_id"),
                                "trip_id", rs.getObject("trip_id"),
                                "delivery_reference", rs.getString("delivery_reference"),
                                "status", rs.getString("status"))
                        : null,
                command.deliveryId(),
                ownerEntityId);

        if (existing == null) {
            throw new ProblemException("m9.delivery.delivery_not_found");
        }

        Instant now = clock.instant();
        Timestamp deliveredAt = "DELIVERED".equals(newStatus) ? Timestamp.from(now) : null;

        jdbc.update(
                """
                UPDATE integration.delivery_record
                   SET status = ?,
                       delivered_at = coalesce(delivered_at, ?),
                       failure_reason = ?,
                       notes = coalesce(?, notes),
                       updated_at = ?
                 WHERE delivery_id = ?
                """,
                newStatus,
                deliveredAt,
                command.failureReason(),
                command.notes(),
                Timestamp.from(now),
                command.deliveryId());

        Map<String, Object> before = Map.of("status", existing.get("status"));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", newStatus);
        if (command.failureReason() != null) {
            after.put("failure_reason", command.failureReason());
        }

        audit.record(AUDIT_STATUS_UPDATED, Subject.of("delivery_record", command.deliveryId()), before, after, scope);

        events.publish(new DeliveryStatusUpdated(
                command.deliveryId(),
                ownerEntityId,
                (UUID) existing.get("trip_id"),
                (String) existing.get("delivery_reference"),
                newStatus,
                now));

        return null;
    }
}
