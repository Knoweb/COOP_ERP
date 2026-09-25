package lk.coopfed.knoweb.m1party.internal.security.role;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lk.coopfed.knoweb.m1party.api.RolePermission;

/**
 * The guardrails of role authoring (doc 19 section 3.2 and 3.3; 21A section 6.1) as plain
 * functions of their inputs, with no database and no Spring, so that a unit test and the
 * property tests of 21A section 9 can run them thousands of times. The handlers read the facts
 * ({@link SecurityRecords}) and call these.
 */
final class RoleRules {

    static final String INSTANCE = "INSTANCE";
    static final String ROLE = "ROLE";

    private RoleRules() {}

    /** A separation-of-duties pair as stored: the two codes in order; owner null for a federation default. */
    record SodRule(UUID sodPairId, String permissionA, String permissionB, String mode, UUID ownerEntityId) {}

    /** The two codes in the order the table keeps them ({@code permission_a < permission_b}). */
    static List<String> ordered(String a, String b) {
        return a.compareTo(b) <= 0 ? List.of(a, b) : List.of(b, a);
    }

    /**
     * The first pair in ROLE mode that a set of permissions holds both halves of, if any. A pair
     * is in ROLE mode for the entity when any of its rows in force (the federation default or
     * the entity's own) says ROLE: an entity may raise a pair, never lower it. Pairs are tried
     * in code order so that the answer, and the message the caller sees, is always the same.
     */
    static Optional<List<String>> roleModeConflict(Collection<String> permissions, Collection<SodRule> rules) {
        Set<String> held = new HashSet<>(permissions);
        TreeSet<String> conflicts = new TreeSet<>();
        for (SodRule rule : rules) {
            if (ROLE.equals(rule.mode()) && held.contains(rule.permissionA()) && held.contains(rule.permissionB())) {
                conflicts.add(rule.permissionA() + "\n" + rule.permissionB());
            }
        }
        if (conflicts.isEmpty()) {
            return Optional.empty();
        }
        String[] first = conflicts.first().split("\n");
        return Optional.of(List.of(first[0], first[1]));
    }

    /** The mode in force for a pair at an entity: ROLE when any row says so, else INSTANCE, else none. */
    static Optional<String> modeInForce(String a, String b, Collection<SodRule> rules) {
        Optional<String> mode = Optional.empty();
        for (SodRule rule : rules) {
            if (rule.permissionA().equals(a) && rule.permissionB().equals(b)) {
                if (ROLE.equals(rule.mode())) {
                    return Optional.of(ROLE);
                }
                mode = Optional.of(rule.mode());
            }
        }
        return mode;
    }

    /** The codes the grantor does not hold: "nobody can grant a permission they do not themselves hold". */
    static List<String> notHeld(Collection<String> wanted, Set<String> grantorHolds) {
        return wanted.stream()
                .filter(code -> !grantorHolds.contains(code))
                .sorted()
                .toList();
    }

    /**
     * The FEDERATION-scope codes in a role that may not carry them: they appear only in
     * federation-owned roles (doc 21 section 3.5), which are the templates and the Federation's
     * own roles.
     */
    static List<String> federationOnly(Collection<SecurityRecords.CatalogueEntry> entries, boolean federationOwned) {
        if (federationOwned) {
            return List.of();
        }
        return entries.stream()
                .filter(SecurityRecords.CatalogueEntry::isFederationScope)
                .map(SecurityRecords.CatalogueEntry::code)
                .sorted()
                .toList();
    }

    /**
     * Whether an entity keeps a holder of {@code gov.user.manage} after a change: it may never
     * lose the last one (doc 19 section 3.2, GUARDRAIL). An entity that has none yet (one being
     * set up) is not refused for staying without.
     *
     * @param before the entity-wide holdings of the permission now
     * @param after  the same holdings once the change is made
     */
    static boolean losesLastUserManager(
            Collection<SecurityRecords.Assignment> before, Collection<SecurityRecords.Assignment> after) {
        long usersBefore = before.stream()
                .map(SecurityRecords.Assignment::userId)
                .distinct()
                .count();
        long usersAfter = after.stream()
                .map(SecurityRecords.Assignment::userId)
                .distinct()
                .count();
        return usersBefore > 0 && usersAfter == 0;
    }

    /**
     * Checks a permission's limits against the permission's {@code limits_schema} (doc 19 section
     * 3.3). The catalogue writes these schemas and they are small, so the subset of JSON Schema
     * read here is small too: an object whose {@code properties} each have a {@code type}
     * (number, integer, string or boolean) and may have {@code minimum} and {@code maximum};
     * {@code required} lists the properties that must be there; any property the schema does not
     * name is refused. There is no JSON Schema library in the version catalogue, and one would be
     * a dependency for four keywords.
     *
     * @return the first problem as the field it concerns, or empty when the limits are valid
     */
    static Optional<String> limitsProblem(Map<String, Object> limits, JsonNode schema) {
        if (limits == null) {
            return Optional.empty();
        }
        JsonNode properties = schema == null ? null : schema.path("properties");
        if (properties == null || !properties.isObject()) {
            return Optional.of("limits");
        }
        for (Map.Entry<String, Object> entry : limits.entrySet()) {
            JsonNode property = properties.get(entry.getKey());
            if (property == null) {
                return Optional.of(entry.getKey());
            }
            if (!valueFits(entry.getValue(), property)) {
                return Optional.of(entry.getKey());
            }
        }
        for (JsonNode required : schema.path("required")) {
            if (!limits.containsKey(required.asText())) {
                return Optional.of(required.asText());
            }
        }
        return Optional.empty();
    }

    private static boolean valueFits(Object value, JsonNode property) {
        String type = property.path("type").asText("");
        switch (type) {
            case "number", "integer" -> {
                if (!(value instanceof Number number)) {
                    return false;
                }
                double d = number.doubleValue();
                if ("integer".equals(type) && d != Math.rint(d)) {
                    return false;
                }
                if (property.has("minimum") && d < property.get("minimum").asDouble()) {
                    return false;
                }
                return !property.has("maximum") || d <= property.get("maximum").asDouble();
            }
            case "string" -> {
                return value instanceof String;
            }
            case "boolean" -> {
                return value instanceof Boolean;
            }
            default -> {
                return false;
            }
        }
    }

    /** Codes added and removed between two permission sets, each sorted. */
    static Map<String, List<String>> diff(Collection<String> before, Collection<String> after) {
        Map<String, List<String>> diff = new HashMap<>();
        diff.put(
                "added",
                after.stream().filter(code -> !before.contains(code)).sorted().toList());
        diff.put(
                "removed",
                before.stream().filter(code -> !after.contains(code)).sorted().toList());
        return diff;
    }

    /** The permission codes of a list, refusing none: duplicates collapse, order is kept. */
    static List<String> codes(Collection<RolePermission> permissions) {
        List<String> codes = new ArrayList<>();
        for (RolePermission permission : permissions) {
            if (!codes.contains(permission.permissionCode())) {
                codes.add(permission.permissionCode());
            }
        }
        return codes;
    }
}
