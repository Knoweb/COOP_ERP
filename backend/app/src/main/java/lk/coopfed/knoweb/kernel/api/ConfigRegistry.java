package lk.coopfed.knoweb.kernel.api;

import java.util.Optional;

public interface ConfigRegistry {

    Optional<String> get(
            String key,
            ScopeContext scope);

    default String getOrDefault(
            String key,
            ScopeContext scope,
            String defaultValue) {
        return get(key, scope).orElse(defaultValue);
    }

    default boolean getBoolean(
            String key,
            ScopeContext scope,
            boolean defaultValue) {
        return get(key, scope)
                .map(Boolean::parseBoolean)
                .orElse(defaultValue);
    }

    default int getInt(
            String key,
            ScopeContext scope,
            int defaultValue) {
        return get(key, scope)
                .map(Integer::parseInt)
                .orElse(defaultValue);
    }
}