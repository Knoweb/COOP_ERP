package lk.coopfed.knoweb.m5inventory.internal.consumers;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Reading M4's event payloads. M5 consumes them as JSON and imports no M4 class: M4 depends on
 * M5's query package, so M5 depending on M4 would be a cycle, and the payload is the contract
 * (24A section 6.1; the field names are those of M4's event records, frozen at M4-05).
 */
final class Payloads {

    private Payloads() {}

    static UUID uuid(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank()
                ? null
                : UUID.fromString(value.asText());
    }

    static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : new BigDecimal(value.asText());
    }

    static Instant instant(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        // Jackson writes an Instant as an ISO text or, with timestamps as numbers, as seconds.
        return value.isNumber()
                ? Instant.ofEpochMilli(Math.round(value.asDouble() * 1000))
                : Instant.parse(value.asText());
    }
}
