package lk.coopfed.knoweb.kernel.internal.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.ProblemException;

/**
 * The value discipline of the register: a value is stored as JSON in the item's type, shown
 * as text, and checked against the item's schema before it is stored (doc 19 section 8:
 * "every value is validated against the item's schema at write").
 *
 * <p>The schema is small on purpose: minimum and maximum (numbers and durations), enum,
 * pattern and max_length (strings). A JSON Schema library would bring more than any item in
 * the register asks for; when one does, this is the one place to grow.
 */
final class ConfigValues {

    private ConfigValues() {}

    /** The stored JSON as the text a caller reads: a string without its quotes, anything else as JSON. */
    static String text(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isTextual() ? value.asText() : value.toString();
    }

    /** Text from a caller into the JSON the item's type stores, or {@code config.value_invalid}. */
    static JsonNode parse(ConfigItem item, String text, ObjectMapper json) {
        if (text == null) {
            throw invalid(item, "null");
        }
        try {
            JsonNode value =
                    switch (item.valueType()) {
                        case STRING -> json.getNodeFactory().textNode(text);
                        case INTEGER -> json.getNodeFactory().numberNode(Long.parseLong(text.strip()));
                        case DECIMAL -> json.getNodeFactory().numberNode(new BigDecimal(text.strip()));
                        case BOOLEAN -> {
                            String lower = text.strip().toLowerCase();
                            if (!lower.equals("true") && !lower.equals("false")) {
                                throw invalid(item, text);
                            }
                            yield json.getNodeFactory().booleanNode(Boolean.parseBoolean(lower));
                        }
                        case DURATION ->
                            json.getNodeFactory()
                                    .textNode(Duration.parse(text.strip()).toString());
                        case JSON -> json.readTree(text);
                    };
            validate(item, value);
            return value;
        } catch (NumberFormatException | java.time.format.DateTimeParseException | JsonProcessingException e) {
            throw invalid(item, text);
        }
    }

    static void validate(ConfigItem item, JsonNode value) {
        JsonNode schema = item.schema() == null ? null : item.schema();

        if (schema == null || schema.isEmpty()) {
            return;
        }

        switch (item.valueType()) {
            case INTEGER, DECIMAL -> {
                BigDecimal number = value.decimalValue();
                if (schema.hasNonNull("minimum")
                        && number.compareTo(schema.get("minimum").decimalValue()) < 0) {
                    throw invalid(item, value.toString());
                }
                if (schema.hasNonNull("maximum")
                        && number.compareTo(schema.get("maximum").decimalValue()) > 0) {
                    throw invalid(item, value.toString());
                }
            }
            case DURATION -> {
                Duration duration = Duration.parse(value.asText());
                if (schema.hasNonNull("minimum")
                        && duration.compareTo(
                                        Duration.parse(schema.get("minimum").asText()))
                                < 0) {
                    throw invalid(item, value.asText());
                }
                if (schema.hasNonNull("maximum")
                        && duration.compareTo(
                                        Duration.parse(schema.get("maximum").asText()))
                                > 0) {
                    throw invalid(item, value.asText());
                }
            }
            case STRING -> {
                String text = value.asText();
                if (schema.hasNonNull("max_length")
                        && text.length() > schema.get("max_length").asInt()) {
                    throw invalid(item, text);
                }
                if (schema.hasNonNull("pattern")
                        && !text.matches(schema.get("pattern").asText())) {
                    throw invalid(item, text);
                }
                if (schema.hasNonNull("enum")) {
                    boolean allowed = false;
                    for (JsonNode option : schema.get("enum")) {
                        allowed |= option.asText().equals(text);
                    }
                    if (!allowed) {
                        throw invalid(item, text);
                    }
                }
            }
            case BOOLEAN, JSON -> {
                // Nothing a schema constrains beyond the type.
            }
        }
    }

    private static ProblemException invalid(ConfigItem item, String text) {
        return new ProblemException("config.value_invalid", Map.of("key", item.key(), "value", String.valueOf(text)));
    }
}
