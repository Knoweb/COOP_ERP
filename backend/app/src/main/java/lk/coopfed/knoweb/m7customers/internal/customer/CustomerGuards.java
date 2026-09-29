package lk.coopfed.knoweb.m7customers.internal.customer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** The guards the customer and account handlers share. */
public final class CustomerGuards {

    public static final Set<String> LANGUAGES = Set.of("en", "si", "ta");

    /** Attribute keys and values are short texts; the column itself caps the whole at 2 kB. */
    static final int ATTRIBUTE_KEY_MAX = 40;

    static final int ATTRIBUTE_VALUE_MAX = 200;

    private CustomerGuards() {}

    /** A society acting for itself: an OWN scope with an entity. */
    public static void requireOwnScope(ScopeContext scope) {
        if (scope == null || !scope.hasActiveScope() || scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("m7.scope.own_required");
        }
    }

    /** Work that concerns no location (a repayment at the office): an entity-wide OWN scope. */
    public static void requireEntityWideScope(ScopeContext scope) {
        requireOwnScope(scope);
        if (scope.locationId() != null) {
            throw new ProblemException("m7.scope.entity_required");
        }
    }

    /** An amount of money of zero or more, in cents; {@code m7.account.amount_invalid} naming the field otherwise. */
    public static java.math.BigDecimal money(java.math.BigDecimal amount, String field) {
        if (amount.signum() < 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new ProblemException("m7.account.amount_invalid", Map.of("field", field));
        }
        return amount.setScale(2);
    }

    public static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ProblemException("m7.field.required", Map.of("field", field));
        }
        return value.strip();
    }

    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public static String language(String value) {
        String language = value == null ? "si" : value;
        if (!LANGUAGES.contains(language)) {
            throw new ProblemException("m7.customer.language_invalid", Map.of("language", language));
        }
        return language;
    }

    /** Tag codes as the society writes them: lower case, no spaces, at most 40 characters, no repeats. */
    public static List<String> tags(List<String> tags) {
        List<String> codes = tags.stream()
                .map(tag -> tag == null ? "" : tag.strip().toLowerCase(java.util.Locale.ROOT))
                .distinct()
                .toList();
        for (String code : codes) {
            if (!code.matches("[a-z0-9][a-z0-9_-]{0,39}")) {
                throw new ProblemException("m7.customer.tag_invalid", Map.of("tag", code));
            }
        }
        return codes;
    }

    public static void attributes(Map<String, String> attributes) {
        for (Map.Entry<String, String> attribute : attributes.entrySet()) {
            String key = attribute.getKey();
            String value = attribute.getValue();
            if (key == null
                    || key.isBlank()
                    || key.length() > ATTRIBUTE_KEY_MAX
                    || value == null
                    || value.length() > ATTRIBUTE_VALUE_MAX) {
                throw new ProblemException("m7.customer.attributes_invalid");
            }
        }
    }

    /**
     * The attributes as the JSON the column stores. The column refuses more than 2 kB (27A); the
     * guard refuses a little less, counted in characters, so the database's check never fires.
     */
    public static String attributesJson(
            com.fasterxml.jackson.databind.ObjectMapper json, Map<String, String> attributes) {
        attributes(attributes);
        try {
            String text = json.writeValueAsString(new java.util.TreeMap<>(attributes));
            if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > ATTRIBUTES_BYTES_MAX) {
                throw new ProblemException("m7.customer.attributes_invalid");
            }
            return text;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ProblemException("m7.customer.attributes_invalid");
        }
    }

    static final int ATTRIBUTES_BYTES_MAX = 1500;

    /** The customer's status, read under the caller's policies; not found when the scope may not see it. */
    public static String customerStatus(JdbcTemplate jdbc, UUID customerId) {
        List<String> status = jdbc.queryForList(
                "select status from customers.customer where customer_id = ? for update", String.class, customerId);
        if (status.isEmpty()) {
            throw new ProblemException("m7.customer.not_found");
        }
        return status.get(0);
    }
}
