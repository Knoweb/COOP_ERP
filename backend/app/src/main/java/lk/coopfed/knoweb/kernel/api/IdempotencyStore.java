package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;

/**
 * Transactional result store for mutating commands.
 *
 * <p>The command interceptor claims the key before executing the handler and
 * completes the same row after the handler result is available. Both operations
 * happen in the handler transaction.
 */
public interface IdempotencyStore {

    record Key(String value, UUID userId, String requestHash) {

        public Key {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Idempotency key must not be blank");
            }

            if (userId == null) {
                throw new IllegalArgumentException("Idempotency key needs an authenticated user");
            }

            if (requestHash == null || requestHash.isBlank()) {
                throw new IllegalArgumentException("Idempotency key needs a request hash");
            }
        }
    }

    record StoredResult(int status, String body) {}

    sealed interface Claim permits Claimed, Replay {}

    record Claimed() implements Claim {}

    record Replay(StoredResult result) implements Claim {}

    Claim claim(Key key);

    void complete(Key key, StoredResult result);
}
