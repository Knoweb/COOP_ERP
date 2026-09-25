package lk.coopfed.knoweb.kernel.api;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * The configuration register (doc 19 section 8; 19A section 11): every configurable item of
 * the platform and its modules in one place, with one resolution rule. A read resolves the
 * most specific value for the caller's scope: the location, then the entity, then the
 * federation-wide value, then the item's default. Reads are cached per instance and the cache
 * is emptied by {@code config.changed.v1}.
 *
 * <p>A write is append-only ({@link #set}): the item must be registered, the value must
 * satisfy its type and schema, the scope must be one the item allows, a sensitive item needs a
 * fresh second factor, and every change is audited ({@code CONFIG_CHANGED}) and published.
 * Law-like values (control prices, segregation pairs) are not configuration; they are data
 * with their own tables.
 */
public interface ConfigRegistry {

    /** The value for this scope, as text, or empty when the key is not registered. */
    Optional<String> get(String key, ScopeContext scope);

    default String getOrDefault(String key, ScopeContext scope, String defaultValue) {
        return get(key, scope).orElse(defaultValue);
    }

    default boolean getBoolean(String key, ScopeContext scope, boolean defaultValue) {
        return get(key, scope).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    default int getInt(String key, ScopeContext scope, int defaultValue) {
        return get(key, scope).map(Integer::parseInt).orElse(defaultValue);
    }

    default Duration getDuration(String key, ScopeContext scope, Duration defaultValue) {
        return get(key, scope).map(Duration::parse).orElse(defaultValue);
    }

    /** Where a value applies: the federation (both null), an entity, or a location of an entity. */
    record ConfigScope(UUID entityId, UUID locationId) {

        public static ConfigScope federation() {
            return new ConfigScope(null, null);
        }

        public static ConfigScope entity(UUID entityId) {
            return new ConfigScope(entityId, null);
        }

        public static ConfigScope location(UUID entityId, UUID locationId) {
            return new ConfigScope(entityId, locationId);
        }
    }

    /**
     * Sets a value for a scope: a new row, effective now; the previous value stays in the
     * history. The caller's scope entity must be the scope's entity (or the Federation for a
     * federation-wide value).
     *
     * @param value  the value as text, in the item's type: "500", "true", "PT5M", or JSON
     * @param reason why, for the audit record; may be null
     * @throws ProblemException {@code config.key_unknown}, {@code config.value_invalid},
     *                          {@code config.scope_invalid}, {@code config.federation_only},
     *                          {@code config.owner_mismatch}, {@code mfa.required}
     */
    void set(String key, ConfigScope scope, String value, ScopeContext ctx, String reason);
}
