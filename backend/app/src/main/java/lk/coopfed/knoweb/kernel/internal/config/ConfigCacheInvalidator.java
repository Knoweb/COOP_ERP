package lk.coopfed.knoweb.kernel.internal.config;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import lk.coopfed.knoweb.kernel.api.ConfigChanged;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.event.CacheFanoutListener;
import org.springframework.stereotype.Component;

/**
 * Empties this instance's cache for a key another instance changed (doc 19 section 8:
 * "cached per instance and invalidated by config.changed.v1"). The instance that made the
 * change emptied its own cache when the change committed (the register does that itself);
 * every other instance hears the event on its own fan-out queue ({@link CacheFanoutListener}),
 * whatever its role, or waits out the cache's expiry where the broker is away. The consumer on
 * the worker's shared queue stays as the belt to those braces.
 */
@Component
class ConfigCacheInvalidator implements CacheFanoutListener {

    static final String CONSUMER = "kernel-config-cache";

    private final JdbcConfigRegistry registry;

    ConfigCacheInvalidator(JdbcConfigRegistry registry) {
        this.registry = registry;
    }

    @EventConsumer(types = ConfigChanged.TYPE, consumer = CONSUMER)
    public void onChanged(ConfigChanged event, ScopeContext scope) {
        registry.invalidate(event.key());
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(ConfigChanged.TYPE);
    }

    @Override
    public void published(String eventType, JsonNode payload) {
        JsonNode key = payload == null ? null : payload.path("key");
        if (key != null && key.isTextual()) {
            registry.invalidate(key.asText());
        }
    }
}
