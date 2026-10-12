package lk.coopfed.knoweb.kernel.internal.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import lk.coopfed.knoweb.kernel.api.DevicePayloadCheck;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.kernel.api.SyncAnomaly;
import lk.coopfed.knoweb.kernel.internal.audit.DeviceAuditWriter;
import lk.coopfed.knoweb.kernel.internal.audit.DeviceAuditWriter.DeviceAudit;
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
 *                    a sequence other than its place in the batch, no payload, a bad time; a
 *                    bundle whose document cannot be read or is not the shape of doc 32 section
 *                    3.1 ({@link BundleShape}); another type whose payload is not the shape its
 *                    module reads ({@link DevicePayloadCheck}; wave 2)
 *   DUPLICATE_ID     the event id was applied before at another sequence (doc 32 section 7:
 *                    "quarantine the later copy")
 *   TOO_LARGE        over sync.event.max_bytes
 *   FORBIDDEN_FIELD  the payload names personal or secret data (AGENTS.md); stored with the
 *                    values of those fields replaced by [removed] (CR-32-1 item 2)
 *   HASH             a document bundle without its content hash, or with another one than its
 *                    content gives (doc 32 section 7)
 * </pre>
 *
 * <p>The till's own audit rows (the {@code audit.*} family of doc 32 section 3.1) are applied here
 * too, into the kernel's audit log, in the same transaction ({@link #auditRow}): the design names
 * the kernel audit store as their handler, and a row that cannot be read (no catalogue type the
 * till may record offline, no subject) is quarantined as SCHEMA like any other malformed event,
 * never dropped.
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

    /** The till's audit rows (doc 32 section 3.1, "audit.*"), by the first segment of the type. */
    static final String AUDIT_FAMILY = "audit.";

    /** An audit catalogue code (doc 18 part D; the facade's rule). */
    private static final Pattern AUDIT_TYPE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    private static final Pattern EVENT_TYPE = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+\\.v[1-9][0-9]*");

    private final JdbcTemplate jdbc;
    private final DeviceEventWriter outbox;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final DeviceAuditWriter deviceAudit;
    private final Map<String, DevicePayloadCheck> payloadChecks;

    EventApplier(
            JdbcTemplate jdbc,
            DeviceEventWriter outbox,
            AuditFacade audit,
            EventPublisher events,
            DeviceAuditWriter deviceAudit,
            List<DevicePayloadCheck> payloadChecks) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.audit = audit;
        this.events = events;
        this.deviceAudit = deviceAudit;
        Map<String, DevicePayloadCheck> byType = new LinkedHashMap<>();
        for (DevicePayloadCheck check : payloadChecks) {
            for (String type : check.eventTypes()) {
                if (byType.put(type, check) != null) {
                    throw new IllegalStateException("Two payload checks for the till event " + type);
                }
            }
        }
        this.payloadChecks = Map.copyOf(byType);
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
                String shape = BundleShape.problemWith(payload);
                if (shape != null) {
                    throw new Refused("SCHEMA", shape);
                }
                if (aggregateId == null) {
                    aggregateId = uuid(payload.get("document"), "document_id", false);
                }
            }
            DevicePayloadCheck check = payloadChecks.get(eventType);
            if (check != null) {
                String shape = check.problemWith(eventType, payload);
                if (shape != null) {
                    throw new Refused("SCHEMA", shape);
                }
            }
            DeviceAudit auditRow = eventType.startsWith(AUDIT_FAMILY)
                    ? auditRow(device, record, seq, eventId, occurredAt, occurredLocal, event, payload)
                    : null;

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
            if (auditRow != null) {
                deviceAudit.write(auditRow);
            }
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
            String stored = "FORBIDDEN_FIELD".equals(refused.reason) ? withoutForbiddenValues(event) : raw;
            return quarantine(
                    device,
                    record,
                    batchId,
                    seq,
                    eventId,
                    eventType,
                    refused.reason,
                    refused.getMessage(),
                    stored,
                    event);
        }
    }

    /**
     * The event as it arrived, with the values of the payload's forbidden fields replaced
     * (CR-32-1 item 2): the one kind of value a quarantine must not keep is the one that put the
     * event there. FORBIDDEN_FIELD is found only in an object event with an object payload.
     */
    private static String withoutForbiddenValues(JsonNode event) {
        ObjectNode copy = ((ObjectNode) event).deepCopy();
        copy.set("payload", DeviceEventWriter.withForbiddenValuesRemoved(event.get("payload")));
        return copy.toString();
    }

    /**
     * The audit row a till sent (doc 18 part D; 26A section 3, the till's audit_event): the
     * catalogue code in {@code event_type_code}, the subject, and what the row may carry beside
     * them. Its id is the event's, its time the till's, its actor the operator of the envelope,
     * its device and sequence the ones it arrived with.
     */
    private DeviceAudit auditRow(
            ScopeContext device,
            DeviceRecord record,
            long seq,
            UUID eventId,
            Instant occurredAt,
            LocalDateTime occurredLocal,
            JsonNode event,
            JsonNode payload)
            throws Refused {
        String code = text(payload, "event_type_code");
        if (code == null || !AUDIT_TYPE_CODE.matcher(code).matches()) {
            throw new Refused("SCHEMA", "an audit row without a catalogue event_type_code");
        }
        if (!deviceAudit.offlineCapturable(code)) {
            throw new Refused("SCHEMA", "the audit type " + code + " is not one a till may record offline");
        }
        String subjectTable = text(payload, "subject_table");
        if (subjectTable == null || subjectTable.isBlank() || subjectTable.length() > 40) {
            throw new Refused("SCHEMA", "an audit row needs subject_table, at most 40 characters");
        }
        UUID subjectId = uuid(payload, "subject_id", true);
        String reasonCode = text(payload, "reason_code");
        if (reasonCode != null && reasonCode.length() > 40) {
            throw new Refused("SCHEMA", "reason_code is longer than 40 characters");
        }
        UUID position = uuid(payload, "till_position_id", false);
        return new DeviceAudit(
                eventId,
                code,
                occurredAt,
                occurredLocal,
                record.ownerEntityId(),
                record.locationId(),
                uuid(event, "actor_user_id", false),
                record.deviceId(),
                position == null ? record.tillPositionId() : position,
                subjectTable,
                subjectId,
                uuid(payload, "document_id", false),
                state(payload, "before_state"),
                state(payload, "after_state"),
                reasonCode,
                text(payload, "reason_text"),
                uuid(payload, "witness_user_id", false),
                seq,
                orElse(uuid(event, "correlation_id", false), device.correlationId()));
    }

    /** A before or after state: absent, or a JSON object. */
    private static String state(JsonNode payload, String name) throws Refused {
        JsonNode value = payload.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw new Refused("SCHEMA", name + " is not an object");
        }
        return value.toString();
    }

    Ack.Outcome quarantine(
            ScopeContext device,
            DeviceRecord record,
            UUID batchId,
            long seq,
            UUID eventId,
            String eventType,
            String reason,
            String detail,
            String raw,
            JsonNode event) {
        UUID quarantineId = Ids.next();
        String type = eventType == null ? null : eventType.substring(0, Math.min(eventType.length(), 200));
        UUID seriesId = null;
        Long docNumber = null;
        if (type != null && isBundle(type) && event != null && event.hasNonNull("payload")) {
            JsonNode payload = event.get("payload");
            if (payload.hasNonNull("document")) {
                JsonNode doc = payload.get("document");
                if (doc.hasNonNull("series_id") && doc.get("series_id").isTextual()) {
                    try {
                        seriesId = UUID.fromString(doc.get("series_id").asText());
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                JsonNode numNode = doc.get("doc_number");
                if (numNode != null && numNode.isIntegralNumber()) {
                    try {
                        long n = Long.parseLong(numNode.asText());
                        if (n > 0) {
                            docNumber = n;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        jdbc.update(
                """
                insert into kernel.sync_quarantine (quarantine_id, device_id, owner_entity_id, location_id, batch_id,
                                                    device_seq, event_id, event_type, reason, detail, raw_event,
                                                    series_id, doc_number)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                raw,
                seriesId,
                docNumber);
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
