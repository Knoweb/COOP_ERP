package lk.coopfed.knoweb.kernel.internal.stub;

import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SeedConfigRegistry implements ConfigRegistry {

    private final Map<String, String> defaults = new ConcurrentHashMap<>();

    public SeedConfigRegistry() {
        defaults.put(
                "business.timezone",
                "Asia/Colombo");
    }

    @Override
    public Optional<String> get(
            String key,
            ScopeContext scope) {
        return Optional.ofNullable(
                defaults.get(key));
    }
}