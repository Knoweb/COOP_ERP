package lk.coopfed.knoweb.kernel.internal.sync;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.kernel.api.SyncAnomaly;
import lk.coopfed.knoweb.kernel.internal.document.BundleHash;
import lk.coopfed.knoweb.kernel.internal.event.DeviceEventWriter;
import lk.coopfed.knoweb.kernel.internal.event.DeviceEventWriter.DeviceEvent;
import lk.coopfed.knoweb.kernel.internal.sync.DeviceDirectory.DeviceRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Applies one event of a batch, inside the chunk's transaction (doc 32 section 3.3 steps 4 and 6;
 * 19A section 8, the loop of BatchIngestor). Either the event is accepted: a row in the
 * device's ledger ({@code kernel.sync_event}) and the event in the outbox with the device as its
 * source, from where the owning module's consumer applies it; or it is quarantined: stored raw
 * with its reason, a row in the ledger, an ALERT and {@code sync.anomaly.v1}. Either way the
 * sequence is used up and the cursor moves past it (S4: never lost, never blocking).
 *
 * <pre>
 *   SCHEMA           not an envelope of doc 19 section 6.1: no event id, an unversioned type,
 *                    a sequence other than its place in the batch, no payload, a bad time; or a
 *                    bundle whose document cannot be read
 *   DUPLICATE_ID     the event id was applied before at another sequence (doc 32 section 7:
 *                    "quarantine the later copy")
 *   TOO_LARGE        over sync.event.max_bytes
 *   FORBIDDEN_FIELD  the payload names personal or secret data (AGENTS.md)
 *   HASH             a document bundle without its content hash, or with another one than its
 *                    content gives (doc 32 section 7)
 * </pre>
 *
 * A business rule is never a reason: a sale that happened is applied and flagged by the module
 * that applies it (S4). That is the consumer's work, after this.
 */
@Component
class EventApplier {

    static final String AUDIT_QUARANTINED = "SYNC_EVENT_QUARANTINED";

    /**
     * The document bundles of doc 32 section 3.1, by type without its version: their payload is
     * a complete document and carries a content hash.
     */
    static final Set<String> BUNDLE_TYPES = Set.of(
            "receipt.issued",
            "receipt.voided",
            "receipt.refunded",
            "grn.captured",
            "grn.confirmed",
            "count.recorded",
            "writeoff.requested",
            "repack.executed",
            "transfer.issued");

    private static final Pattern EVENT_TYPE = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+\\.v[1-9][0-9]*");

    private final JdbcTemplate jdbc;
    private final DeviceEventWriter outbox;
    private final AuditFacade audit;
    private final EventPublisher events;

    EventApplier(JdbcTemplate jdbc, DeviceEventWriter outbox, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.audit = audit;
        this.events = events;
    }

    /** A reason to refuse one event, found while reading it. */
    private static final class Refused extends Exception {
        final String reason;

        Refused(String reason, String detail) {
            super(detail, null, false, false);
            this.reason = reason;
        }
    }

    Ack.Outcome apply(
            ScopeContext device, DeviceRecord record, UUID batchId, long seq, JsonNode event, int maxEventBytes) {
        String raw = event == null ? "null" : event.toString();
        UUID eventId = null;
        String eventType = null;
        try {
            if (event == null || !event.isObject()) {
                throw new Refused("SCHEMA", "the event is not an object");
            }
            eventId = uuid(event, "event_id", true);
            eventType = text(event, "event_type");
            if (eventType == null || !EVENT_TYPE.matcher(eventType).matches()) {
                throw new Refused("SCHEMA", "event_type is missing or not dotted and versioned");
            }
            JsonNode sequence = event.get("device_seq");
            if (sequence == null || !sequence.canConvertToLong() || sequence.asLong() != seq) {
                throw new Refused("SCHEMA", "device_seq is not " + seq + ", its place in the batch");
            }
            JsonNode payload = event.get("payload");
            if (payload == null || !payload.isObject()) {
                throw new Refused("SCHEMA", "payload is missing or not an object");
            }
            Instant occurredAt = instant(event, "occurred_at");
            LocalDateTime occurredLocal = localDateTime(event, "occurred_local");

            if (appliedBefore(eventId)) {
                throw new Refused("DUPLICATE_ID", "event_id was applied before at another sequence");
            }
            if (raw.getBytes(StandardCharsets.UTF_8).length > maxEventBytes) {
                throw new Refused("TOO_LARGE", "the event is over " + maxEventBytes + " bytes");
            }
            String forbidden = DeviceEventWriter.forbiddenFieldIn(payload);
            if (forbidden != null) {
                throw new Refused("FORBIDDEN_FIELD", "the payload carries the field " + forbidden);
            }
            UUID aggregateId = uuid(event, "aggregate_id", false);
            if (isBundle(eventType)) {
                String declared = text(event, "content_hash");
                if (declared == null) {
                    throw new Refused("HASH", "a document bundle without content_hash");
                }
                String computed;
                try {
                    computed = BundleHash.of(payload.get("document"), payload.get("lines"));
                } catch (RuntimeException unreadable) {
                    throw new Refused("SCHEMA", "the bundle's document cannot be read: " + unreadable.getMessage());
                }
                if (!computed.equalsIgnoreCase(declared.strip())) {
                    throw new Refused("HASH", "content_hash does not match the document");
                }
                if (aggregateId == null) {
                    aggregateId = uuid(payload.get("document"), "document_id", false);
                }
            }

            jdbc.update(
                    """
                    insert into kernel.sync_event (device_id, device_seq, owner_entity_id, event_id, event_type,
                                                   batch_id, outcome, reason)
                    values (?, ?, ?, ?, ?, ?, 'APPLIED', null)
                    """,
                    record.deviceId(),
                    seq,
                    record.ownerEntityId(),
                    eventId,
                    eventType,
                    batchId);
            String aggregateType = text(event, "aggregate_type");
            outbox.write(new DeviceEvent(
                    eventId,
                    eventType,
                    occurredAt,
                    occurredLocal,
                    record.deviceId(),
                    seq,
                    record.ownerEntityId(),
                    record.locationId(),
                    aggregateType == null ? aggregateTypeOf(eventType) : aggregateType,
                    aggregateId == null ? eventId : aggregateId,
                    orElse(uuid(event, "correlation_id", false), device.correlationId()),
                    uuid(event, "causation_id", false),
                    uuid(event, "actor_user_id", false),
                    text(event, "engine_version"),
                    payload.toString()));
            return new Ack.Outcome(seq, eventId, Ack.APPLIED, null);
        } catch (Refused refused) {
            return quarantine(
                    device, record, batchId, seq, eventId, eventType, refused.reason, refused.getMessage(), raw);
        }
    }

    private Ack.Outcome quarantine(
            ScopeContext device,
            DeviceRecord record,
            UUID batchId,
            long seq,
            UUID eventId,
            String eventType,
            String reason,
            String detail,
            String raw) {
        UUID quarantineId = Ids.next();
        String type = eventType == null ? null : eventType.substring(0, Math.min(eventType.length(), 200));
        jdbc.update(
                """
                insert into kernel.sync_quarantine (quarantine_id, device_id, owner_entity_id, location_id, batch_id,
                                                    device_seq, event_id, event_type, reason, detail, raw_event)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                quarantineId,
                record.deviceId(),
                record.ownerEntityId(),
                record.locationId(),
                batchId,
                seq,
                eventId,
                type,
                reason,
                detail,
                raw);
        jdbc.update(
                """
                insert into kernel.sync_event (device_id, device_seq, owner_entity_id, event_id, event_type,
                                               batch_id, outcome, reason)
                values (?, ?, ?, ?, ?, ?, 'QUARANTINED', ?)
                """,
                record.deviceId(),
                seq,
                record.ownerEntityId(),
                eventId,
                type,
                batchId,
                reason);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("deviceSeq", seq);
        after.put("eventId", eventId);
        after.put("eventType", type);
        after.put("reason", reason);
        after.put("batchId", batchId);
        audit.record(AUDIT_QUARANTINED, Subject.of("sync_quarantine", quarantineId), null, after, device, detail);
        events.publish(new SyncAnomaly(quarantineId, record.deviceId(), "QUARANTINED", seq, reason));
        return new Ack.Outcome(seq, eventId, Ack.QUARANTINED, reason);
    }

    /** What the ledger says of sequences already applied: DUPLICATE, or QUARANTINED with its reason. */
    List<Ack.Outcome> fromState(UUID deviceId, long fromSeq, long toSeq) {
        Map<Long, Ack.Outcome> known = new LinkedHashMap<>();
        jdbc.query(
                """
                select device_seq, event_id, outcome, reason from kernel.sync_event
                 where device_id = ? and device_seq between ? and ?
                """,
                rs -> {
                    long seq = rs.getLong("device_seq");
                    boolean quarantined = "QUARANTINED".equals(rs.getString("outcome"));
                    known.put(
                            seq,
                            new Ack.Outcome(
                                    seq,
                                    rs.getObject("event_id", UUID.class),
                                    quarantined ? Ack.QUARANTINED : Ack.DUPLICATE,
                                    quarantined ? rs.getString("reason") : null));
                },
                deviceId,
                fromSeq,
                toSeq);
        List<Ack.Outcome> outcomes = new java.util.ArrayList<>();
        for (long seq = fromSeq; seq <= toSeq; seq++) {
            outcomes.add(known.getOrDefault(seq, new Ack.Outcome(seq, null, Ack.DUPLICATE, null)));
        }
        return outcomes;
    }

    static boolean isBundle(String eventType) {
        int version = eventType.lastIndexOf(".v");
        return version > 0 && BUNDLE_TYPES.contains(eventType.substring(0, version));
    }

    /** The outbox's rule for central events: the part before the action (receipt.issued.v1 -> receipt). */
    private static String aggregateTypeOf(String eventType) {
        String[] parts = eventType.split("\\.");
        return parts[parts.length - 3];
    }

    private boolean appliedBefore(UUID eventId) {
        Boolean found = jdbc.queryForObject(
                "select exists (select 1 from kernel.sync_event where event_id = ? and outcome = 'APPLIED')",
                Boolean.class,
                eventId);
        return Boolean.TRUE.equals(found);
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static UUID uuid(JsonNode node, String name, boolean required) throws Refused {
        String value = text(node, name);
        if (value == null) {
            if (required) {
                throw new Refused("SCHEMA", name + " is missing");
            }
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            throw new Refused("SCHEMA", name + " is not a UUID");
        }
    }

    private static Instant instant(JsonNode node, String name) throws Refused {
        String value = text(node, name);
        if (value == null) {
            throw new Refused("SCHEMA", name + " is missing");
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException notAnInstant) {
            throw new Refused("SCHEMA", name + " is not an instant");
        }
    }

    private static LocalDateTime localDateTime(JsonNode node, String name) throws Refused {
        String value = text(node, name);
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException notALocalTime) {
            throw new Refused("SCHEMA", name + " is not a local date and time");
        }
    }

    private static UUID orElse(UUID value, UUID fallback) {
        return value == null ? fallback : value;
    }
}
