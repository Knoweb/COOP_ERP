package lk.coopfed.knoweb.kernel.internal.config;

import lk.coopfed.knoweb.kernel.api.ConfigChanged;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * Empties this instance's cache for a key another instance changed (doc 19 section 8:
 * "cached per instance and invalidated by config.changed.v1"). The instance that made the
 * change emptied its own cache at once; the others learn it here, or by the cache's expiry
 * where the consumer runtime does not run.
 */
@Component
class ConfigCacheInvalidator {

    static final String CONSUMER = "kernel-config-cache";

    private final JdbcConfigRegistry registry;

    ConfigCacheInvalidator(JdbcConfigRegistry registry) {
        this.registry = registry;
    }

    @EventConsumer(types = ConfigChanged.TYPE, consumer = CONSUMER)
    public void onChanged(ConfigChanged event, ScopeContext scope) {
        registry.invalidate(event.key());
    }
}
