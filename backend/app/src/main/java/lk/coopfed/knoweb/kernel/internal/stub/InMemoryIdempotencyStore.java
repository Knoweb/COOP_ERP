package lk.coopfed.knoweb.kernel.internal.stub;

import lk.coopfed.knoweb.kernel.api.IdempotencyStore;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryIdempotencyStore
        implements IdempotencyStore {

    private final Set<String> keys = ConcurrentHashMap.newKeySet();

    @Override
    public boolean claim(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                    "Idempotency key must not be blank");
        }

        return keys.add(key);
    }

    @Override
    public void release(String key) {
        if (key != null) {
            keys.remove(key);
        }
    }
}