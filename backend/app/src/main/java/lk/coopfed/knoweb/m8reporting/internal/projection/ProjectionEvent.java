package lk.coopfed.knoweb.m8reporting.internal.projection;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One event as a projection sees it: the envelope the kernel hands a consumer of every type
 * ({@code "*"}: eventType, eventId, ownerEntityId, occurredAt, payload; EventConsumerDispatcher)
 * and the payload inside. The projections subscribe that way because the envelope carries what
 * the payload lacks: the event's id and the time it happened, which the freshness of a row is.
 *
 * <p>The payloads are read as JSON by field name: M8 imports no event record of another module
 * (28A section 4: the read side calls no layer-2 api), and the field names are the contract.
 */
public record ProjectionEvent(String type, UUID eventId, UUID ownerEntityId, Instant occurredAt, JsonNode payload) {

    /** Reads the kernel's envelope; an envelope without a type, id or time is not an event. */
    public static ProjectionEvent of(JsonNode envelope) {
        String type = envelope.path("eventType").asText(null);
        String id = envelope.path("eventId").asText(null);
        String occurred = envelope.path("occurredAt").asText(null);
        if (type == null || id == null || occurred == null) {
            throw new IllegalArgumentException("Not an event envelope: it lacks the type, the id or the time");
        }
        String owner = envelope.path("ownerEntityId").asText(null);
        return new ProjectionEvent(
                type,
                UUID.fromString(id),
                owner == null ? null : UUID.fromString(owner),
                Instant.parse(occurred),
                envelope.path("payload"));
    }

    public UUID uuid(String field) {
        return uuid(payload, field);
    }

    public String text(String field) {
        return text(payload, field);
    }

    public BigDecimal decimal(String field) {
        return decimal(payload, field);
    }

    public LocalDate date(String field) {
        String value = text(field);
        return value == null ? null : LocalDate.parse(value);
    }

    public Instant instant(String field) {
        String value = text(field);
        return value == null ? null : Instant.parse(value);
    }

    public static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : UUID.fromString(value);
    }

    public static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    public static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : new BigDecimal(value.asText());
    }
}
