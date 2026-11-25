package lk.coopfed.knoweb.m9integration.internal.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.ConfirmCodSettlement;
import lk.coopfed.knoweb.m9integration.api.ConfirmPayment;
import lk.coopfed.knoweb.m9integration.api.InitiatePayment;
import lk.coopfed.knoweb.m9integration.api.PaymentOrchestrator;
import lk.coopfed.knoweb.m9integration.api.RetryPayment;
import lk.coopfed.knoweb.m9integration.query.PaymentIntentView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of the provider-neutral payment orchestrator (M3-07).
 */
@Service
@Transactional
public class PaymentOrchestratorImpl implements PaymentOrchestrator {

    private final InitiatePaymentHandler initiateHandler;
    private final ConfirmPaymentHandler confirmHandler;
    private final ConfirmCodSettlementHandler confirmCodHandler;
    private final RetryPaymentHandler retryHandler;
    private final JdbcTemplate jdbc;
    private final PaymentRowMapper rowMapper;

    public PaymentOrchestratorImpl(
            InitiatePaymentHandler initiateHandler,
            ConfirmPaymentHandler confirmHandler,
            ConfirmCodSettlementHandler confirmCodHandler,
            RetryPaymentHandler retryHandler,
            JdbcTemplate jdbc) {
        this.initiateHandler = initiateHandler;
        this.confirmHandler = confirmHandler;
        this.confirmCodHandler = confirmCodHandler;
        this.retryHandler = retryHandler;
        this.jdbc = jdbc;
        this.rowMapper = new PaymentRowMapper(null);
    }

    @Override
    public PaymentIntentView initiate(InitiatePayment command, ScopeContext scope) {
        return initiateHandler.handle(command, scope);
    }

    @Override
    public PaymentIntentView confirm(ConfirmPayment command, ScopeContext scope) {
        return confirmHandler.handle(command, scope);
    }

    @Override
    public PaymentIntentView confirmCod(ConfirmCodSettlement command, ScopeContext scope) {
        return confirmCodHandler.handle(command, scope);
    }

    @Override
    public PaymentIntentView retry(RetryPayment command, ScopeContext scope) {
        return retryHandler.handle(command, scope);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentIntentView> findById(UUID paymentId, ScopeContext scope) {
        if (paymentId == null) {
            return Optional.empty();
        }
        List<PaymentIntentView> list =
                jdbc.query("SELECT * FROM integration.payment_intent WHERE payment_id = ?", rowMapper, paymentId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentIntentView> findByIdempotencyKey(String idempotencyKey, ScopeContext scope) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        UUID ownerEntityId = scope != null && scope.entityId() != null
                ? scope.entityId()
                : UUID.fromString("0190e9f0-0000-7000-8000-000000000001");
        List<PaymentIntentView> list = jdbc.query(
                "SELECT * FROM integration.payment_intent WHERE owner_entity_id = ? AND idempotency_key = ?",
                rowMapper,
                ownerEntityId,
                idempotencyKey.trim());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }
}
