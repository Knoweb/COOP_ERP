package lk.coopfed.knoweb.kernel.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The shape of a till's event that is not a document bundle, as the module that applies it reads
 * it (wave 2, decided 6 October 2026: docs/progress/deviations/2026-10-06-wave2-till-facts-at-the-gateway.md
 * (1)). The sync gateway quarantines a malformed payload before it reaches the outbox (doc 32
 * section 3.3 step 6: "validate envelope and schema ... store raw in sync_quarantine with the
 * reason"), so every consumer of the same event sees the same outcome: a payload one module
 * cannot read never reaches another that could. The gateway checks the bundles itself (doc 32
 * section 3.1); for any other type it asks the module that owns the type, through this
 * interface, so that the kernel never imports a module.
 *
 * <p>A check is about shape only: a field that must be there, a value of the right kind (a UUID,
 * an instant, a decimal). A business rule is never a reason (AGENTS.md: a fact is applied and
 * flagged); that stays in the module's consumer.
 */
public interface DevicePayloadCheck {

    /** The event types (dotted and versioned, "till_session.opened.v1") this check reads. */
    Set<String> eventTypes();

    /**
     * What is wrong with the payload, in a sentence for the person who reads the quarantine, or
     * null when it has the shape the module reads. Never throws for a malformed payload.
     */
    String problemWith(String eventType, JsonNode payload);

    // ---- the kinds of value a till writes (till/core Facts.kt), shared with the gateway's own
    // bundle check so that both read a value the same way ----

    /** A decimal as the till writes money, quantity and price: text, plain digits ("1450.00", "-5"). */
    Pattern DECIMAL_TEXT = Pattern.compile("-?[0-9]{1,18}(\\.[0-9]{1,6})?");

    /** The field's value, or null when it is absent or JSON null. */
    static JsonNode present(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value;
    }

    static boolean isUuid(JsonNode value) {
        if (value == null || !value.isTextual()) {
            return false;
        }
        try {
            UUID.fromString(value.asText());
            return true;
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
    }

    static boolean isInstant(JsonNode value) {
        if (value == null || !value.isTextual()) {
            return false;
        }
        try {
            Instant.parse(value.asText());
            return true;
        } catch (DateTimeParseException notAnInstant) {
            return false;
        }
    }

    static boolean isDate(JsonNode value) {
        if (value == null || !value.isTextual()) {
            return false;
        }
        try {
            LocalDate.parse(value.asText());
            return true;
        } catch (DateTimeParseException notADate) {
            return false;
        }
    }

    static boolean isDecimalText(JsonNode value) {
        return value != null
                && value.isTextual()
                && DECIMAL_TEXT.matcher(value.asText()).matches();
    }

    /**
     * The first of {@code fields} present in {@code node} whose value is not of the kind, as
     * "name is not a ...", or null when every present one is.
     */
    static String firstNot(JsonNode node, List<String> fields, Predicate<JsonNode> kind, String what) {
        for (String field : fields) {
            JsonNode value = present(node, field);
            if (value != null && !kind.test(value)) {
                return field + " is not " + what;
            }
        }
        return null;
    }
}
