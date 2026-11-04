package lk.coopfed.knoweb.m9integration.query;

import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * Public query seam for payment intents and transaction statuses (M3-07).
 */
public interface PaymentIntentQueries {

    /**
     * Finds a payment intent by its unique ID.
     */
    Optional<PaymentIntentView> findById(UUID paymentId, ScopeContext scope);

    /**
     * Finds a payment intent by its idempotency deduplication key.
     */
    Optional<PaymentIntentView> findByDedupKey(String dedupKey, ScopeContext scope);

    /**
     * Finds a payment intent by its order reference.
     */
    Optional<PaymentIntentView> findByOrderReference(String orderReference, ScopeContext scope);
}
