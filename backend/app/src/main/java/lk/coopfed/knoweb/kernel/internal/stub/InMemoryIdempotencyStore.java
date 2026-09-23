package lk.coopfed.knoweb.kernel.internal.stub;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lk.coopfed.knoweb.kernel.api.IdempotencyStore;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.springframework.stereotype.Component;

/**
 * 17A stub: one instance, no expiry. 19A replaces it with the kernel.idempotency_key
 * table so every instance answers alike.
 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private record Entry(String requestHash, StoredResult result) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public Optional<StoredResult> find(Key key) {
        Entry entry = entries.get(compound(key));

        if (entry == null) {
            return Optional.empty();
        }

        if (!entry.requestHash().equals(key.requestHash())) {
            throw new ProblemException("idempotency.request_mismatch", Map.of("key", key.value()));
        }

        return Optional.of(entry.result());
    }

    @Override
    public void store(Key key, StoredResult result) {
        entries.put(compound(key), new Entry(key.requestHash(), result));
    }

    private static String compound(Key key) {
        return key.userId() + ":" + key.value();
    }
}
