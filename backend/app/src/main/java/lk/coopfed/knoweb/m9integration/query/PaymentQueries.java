package lk.coopfed.knoweb.m9integration.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * Public query seam for payment intents and transaction status (M3-07).
 */
public interface PaymentQueries {

    Optional<PaymentIntentView> findById(UUID paymentId, ScopeContext scope);

    Optional<PaymentIntentView> findByIdempotencyKey(String idempotencyKey, ScopeContext scope);

    List<PaymentIntentView> findByOrderReference(String orderReference, ScopeContext scope);

    List<PaymentIntentView> listRecent(int limit, ScopeContext scope);
}
