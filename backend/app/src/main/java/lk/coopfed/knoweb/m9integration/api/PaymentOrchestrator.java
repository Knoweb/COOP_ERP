package lk.coopfed.knoweb.m9integration.api;

import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.query.PaymentIntentView;

/**
 * Provider-neutral payment orchestration interface (Wave 7, PGL-M3-07).
 * Coordinates online payment processing, dynamic LankaQR generation, Cash on Delivery (COD) tracking,
 * and resilient idempotent settlement.
 */
public interface PaymentOrchestrator {

    PaymentIntentView initiate(InitiatePayment command, ScopeContext scope);

    PaymentIntentView confirm(ConfirmPayment command, ScopeContext scope);

    PaymentIntentView confirmCod(ConfirmCodSettlement command, ScopeContext scope);

    PaymentIntentView retry(RetryPayment command, ScopeContext scope);

    Optional<PaymentIntentView> findById(UUID paymentId, ScopeContext scope);

    Optional<PaymentIntentView> findByIdempotencyKey(String idempotencyKey, ScopeContext scope);
}
