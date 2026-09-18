package lk.coopfed.knoweb.kernel.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Result store for mutating commands (doc 14 §1.3; doc 18 Part E, table
 * {@code idempotency_key}). The same key from the same user returns the same
 * result from any instance; a reused key with a different request body is
 * rejected. Rows expire after 24 hours.
 *
 * <p>The kernel's command interceptor calls {@link #find} before the handler and
 * {@link #store} after it (19A §3); handlers never touch this directly.
 */
public interface IdempotencyStore {

    /**
     * @param value       the client-supplied Idempotency-Key header
     * @param userId      the caller; keys are scoped per user
     * @param requestHash SHA-256 of the canonical request body
     */
    record Key(
            String value,
            UUID userId,
            String requestHash) {

        public Key {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Idempotency key must not be blank");
            }
            if (requestHash == null || requestHash.isBlank()) {
                throw new IllegalArgumentException("Idempotency key needs a request hash");
            }
        }
    }

    /** The response recorded for a key: HTTP status and the serialised body. */
    record StoredResult(
            int status,
            String body) {
    }

    /**
     * Returns the result previously stored for this key by this user, if any.
     *
     * @throws ProblemException {@code idempotency.request_mismatch} when the key was
     *                          used before with a different request hash
     */
    Optional<StoredResult> find(Key key);

    /** Records the result of the first execution under this key. */
    void store(
            Key key,
            StoredResult result);
}
