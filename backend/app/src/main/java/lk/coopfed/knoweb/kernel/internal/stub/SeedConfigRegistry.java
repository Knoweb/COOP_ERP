package lk.coopfed.knoweb.kernel.internal.stub;

import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 17A stub: seed defaults only, the same for every scope. 19A K-11 replaces it with the
 * config_item and config_value tables, resolved location, then entity, then default.
 *
 * <p>The one default so far repeats nothing: the business time zone is read from
 * {@code coop-erp.business-timezone}, the same property the business date uses.
 */
@Component
public class SeedConfigRegistry implements ConfigRegistry {

    private final Map<String, String> defaults = new ConcurrentHashMap<>();

    public SeedConfigRegistry(@Value("${coop-erp.business-timezone}") String businessTimezone) {
        defaults.put("business.timezone", businessTimezone);
    }

    public void addDefaults(Map<String, String> items) {
        defaults.putAll(items);
    }

    @Override
    public Optional<String> get(
            String key,
            ScopeContext scope) {
        return Optional.ofNullable(defaults.get(key));
    }
}
