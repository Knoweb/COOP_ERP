package lk.coopfed.knoweb.m9integration.internal.queries;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.internal.payment.PaymentRowMapper;
import lk.coopfed.knoweb.m9integration.query.PaymentIntentQueries;
import lk.coopfed.knoweb.m9integration.query.PaymentIntentView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public query service implementation for payment intents and transaction statuses (M3-07).
 */
@Service
@Transactional(readOnly = true)
public class PaymentIntentQueriesImpl implements PaymentIntentQueries {

    private final JdbcTemplate jdbc;
    private final PaymentRowMapper rowMapper;

    public PaymentIntentQueriesImpl(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.rowMapper = new PaymentRowMapper(json);
    }

    @Override
    public Optional<PaymentIntentView> findById(UUID paymentId, ScopeContext scope) {
        String sql =
                """
                SELECT payment_id, owner_entity_id, order_reference, customer_reference,
                       amount, currency, payment_method, payment_status, provider_id,
                       provider_reference, qr_payload, description, failure_reason,
                       dedup_key, metadata, created_at, settled_at, updated_at, expires_at
                FROM integration.payment_intent
                WHERE payment_id = ?
                """;
        List<PaymentIntentView> results = jdbc.query(sql, rowMapper, paymentId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    public Optional<PaymentIntentView> findByDedupKey(String dedupKey, ScopeContext scope) {
        String sql =
                """
                SELECT payment_id, owner_entity_id, order_reference, customer_reference,
                       amount, currency, payment_method, payment_status, provider_id,
                       provider_reference, qr_payload, description, failure_reason,
                       dedup_key, metadata, created_at, settled_at, updated_at, expires_at
                FROM integration.payment_intent
                WHERE dedup_key = ?
                """;
        List<PaymentIntentView> results = jdbc.query(sql, rowMapper, dedupKey);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    public Optional<PaymentIntentView> findByOrderReference(String orderReference, ScopeContext scope) {
        String sql =
                """
                SELECT payment_id, owner_entity_id, order_reference, customer_reference,
                       amount, currency, payment_method, payment_status, provider_id,
                       provider_reference, qr_payload, description, failure_reason,
                       dedup_key, metadata, created_at, settled_at, updated_at, expires_at
                FROM integration.payment_intent
                WHERE order_reference = ?
                ORDER BY created_at DESC
                LIMIT 1
                """;
        List<PaymentIntentView> results = jdbc.query(sql, rowMapper, orderReference);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }
}
