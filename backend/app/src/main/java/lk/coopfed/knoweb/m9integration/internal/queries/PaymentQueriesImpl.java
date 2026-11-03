package lk.coopfed.knoweb.m9integration.internal.queries;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.internal.payment.PaymentRowMapper;
import lk.coopfed.knoweb.m9integration.query.PaymentIntentView;
import lk.coopfed.knoweb.m9integration.query.PaymentQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public query service implementation for payment intents and transaction statuses (M3-07).
 */
@Service
@Transactional(readOnly = true)
public class PaymentQueriesImpl implements PaymentQueries {

    private final JdbcTemplate jdbc;
    private final PaymentRowMapper rowMapper;

    public PaymentQueriesImpl(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.rowMapper = new PaymentRowMapper(json);
    }

    @Override
    public Optional<PaymentIntentView> findById(UUID paymentId, ScopeContext scope) {
        if (paymentId == null) {
            return Optional.empty();
        }
        List<PaymentIntentView> results =
                jdbc.query("SELECT * FROM integration.payment_intent WHERE payment_id = ?", rowMapper, paymentId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Optional<PaymentIntentView> findByIdempotencyKey(String idempotencyKey, ScopeContext scope) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        UUID ownerEntityId = scope != null && scope.entityId() != null
                ? scope.entityId()
                : UUID.fromString("0190e9f0-0000-7000-8000-000000000001");
        List<PaymentIntentView> results = jdbc.query(
                "SELECT * FROM integration.payment_intent WHERE owner_entity_id = ? AND idempotency_key = ?",
                rowMapper,
                ownerEntityId,
                idempotencyKey.trim());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<PaymentIntentView> findByOrderReference(String orderReference, ScopeContext scope) {
        if (orderReference == null || orderReference.isBlank()) {
            return List.of();
        }
        return jdbc.query(
                "SELECT * FROM integration.payment_intent WHERE order_reference = ? ORDER BY created_at DESC",
                rowMapper,
                orderReference.trim());
    }

    @Override
    public List<PaymentIntentView> listRecent(int limit, ScopeContext scope) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query(
                "SELECT * FROM integration.payment_intent ORDER BY created_at DESC LIMIT ?", rowMapper, safeLimit);
    }
}
