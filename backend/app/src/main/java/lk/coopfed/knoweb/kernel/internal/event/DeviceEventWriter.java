package lk.coopfed.knoweb.kernel.internal.event;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Puts an event a till uploaded into the outbox (19A section 8: "outbox.writeFromDevice(e):
 * re-publish into the backbone with source = device, seq = e.seq"). From there the relay hands it
 * to the broker like any central event, in the device's sequence order, and the owning module's
 * {@code @EventConsumer} applies it once (doc 19 section 6.3). So nothing central does with a
 * till's fact bypasses the event framework.
 *
 * <p>Separate from {@link OutboxWriter}, which publishes the central events of a handler: the
 * envelope of a device event is the till's (its event id, its sequence, its clock, its operator),
 * not the one a central handler's transaction would give it, and its payload is the till's
 * document, bounded by the sync limits rather than the 8 KB of a central event. It is not an
 * {@code EventPublisher} either: a module never writes a device event, only the ingestor does.
 */
@Component
public class DeviceEventWriter {

    /** One till event with the envelope fields of doc 19 section 6.1. */
    public record DeviceEvent(
            UUID eventId,
            String eventType,
            Instant occurredAt,
            LocalDateTime occurredLocal,
            UUID deviceId,
            long deviceSeq,
            UUID ownerEntityId,
            UUID locationId,
            String aggregateType,
            UUID aggregateId,
            UUID correlationId,
            UUID causationId,
            UUID actorUserId,
            String engineVersion,
            String payloadJson) {}

    private final JdbcTemplate jdbc;

    public DeviceEventWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the event, in the caller's transaction, whose scope must be the device's entity. */
    public void write(DeviceEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A device event is written inside the ingestion transaction");
        }
        jdbc.update(
                """
                INSERT INTO kernel.event_outbox (
                    event_id, event_type, occurred_at, occurred_local, source, source_seq,
                    owner_entity_id, location_id, aggregate_type, aggregate_id,
                    correlation_id, causation_id, actor_user_id, engine_version, payload, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), NULL)
                """,
                event.eventId(),
                event.eventType(),
                Timestamp.from(event.occurredAt()),
                event.occurredLocal() == null ? null : Timestamp.valueOf(event.occurredLocal()),
                event.deviceId().toString(),
                event.deviceSeq(),
                event.ownerEntityId(),
                event.locationId(),
                event.aggregateType(),
                event.aggregateId(),
                event.correlationId(),
                event.causationId(),
                event.actorUserId(),
                event.engineVersion(),
                event.payloadJson());
    }

    /**
     * The first field of the payload, at any depth, whose name says it holds personal or secret
     * data (a phone number, a NIC, a PIN, a token: AGENTS.md), or null when there is none. The
     * same word rule as the central outbox ({@link OutboxWriter#isForbiddenField}).
     */
    public static String forbiddenFieldIn(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (OutboxWriter.isForbiddenField(field.getKey())) {
                    return field.getKey();
                }
                String inside = forbiddenFieldIn(field.getValue());
                if (inside != null) {
                    return inside;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String inside = forbiddenFieldIn(child);
                if (inside != null) {
                    return inside;
                }
            }
        }
        return null;
    }
}
