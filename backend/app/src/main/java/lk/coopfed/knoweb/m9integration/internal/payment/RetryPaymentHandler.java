package lk.coopfed.knoweb.m9integration.internal.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m9integration.api.PaymentRetried;
import lk.coopfed.knoweb.m9integration.api.RetryPayment;
import lk.coopfed.knoweb.m9integration.internal.payment.adapter.PaymentAdapterRegistry;
import lk.coopfed.knoweb.m9integration.internal.payment.adapter.PaymentProviderAdapter;
import lk.coopfed.knoweb.m9integration.internal.payment.adapter.PaymentProviderAdapter.ProviderInitiationRequest;
import lk.coopfed.knoweb.m9integration.internal.payment.adapter.PaymentProviderAdapter.ProviderInitiationResult;
import lk.coopfed.knoweb.m9integration.query.PaymentIntentView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles retrying a pending or failed payment with a provider (M3-07).
 */
@Service
@CommandHandler(permission = "int.payment.manage")
public class RetryPaymentHandler implements Handles<RetryPayment, PaymentIntentView> {

    public static final String AUDIT_PAYMENT_RETRIED = "PAYMENT_RETRIED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;
    private final PaymentAdapterRegistry adapterRegistry;
    private final ObjectMapper json;
    private final PaymentRowMapper rowMapper;

    public RetryPaymentHandler(
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events,
            Clock clock,
            PaymentAdapterRegistry adapterRegistry,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.adapterRegistry = adapterRegistry;
        this.json = json != null ? json : new ObjectMapper();
        this.rowMapper = new PaymentRowMapper(this.json);
    }

    @Override
    @Transactional
    public PaymentIntentView handle(RetryPayment command, ScopeContext scope) {
        if (command.paymentId() == null) {
            throw new ProblemException("m9.payment.not_found");
        }

        List<PaymentIntentView> list = jdbc.query(
                "SELECT * FROM integration.payment_intent WHERE payment_id = ?", rowMapper, command.paymentId());

        if (list.isEmpty()) {
            throw new ProblemException("m9.payment.not_found");
        }

        PaymentIntentView existing = list.get(0);

        // If already paid, return idempotently
        if ("PAID".equalsIgnoreCase(existing.status())) {
            return existing;
        }

        if ("CANCELLED".equalsIgnoreCase(existing.status())) {
            throw new ProblemException("m9.payment.already_cancelled");
        }

        PaymentProviderAdapter adapter = adapterRegistry.resolve(command.provider(), existing.paymentMethod());

        ProviderInitiationRequest providerReq = new ProviderInitiationRequest(
                existing.paymentId(),
                existing.orderReference(),
                existing.customerRef(),
                existing.amount(),
                existing.currency(),
                existing.paymentMethod(),
                existing.description(),
                existing.metadata());

        ProviderInitiationResult providerRes = adapter.initiate(providerReq);

        Map<String, Object> updatedMeta = new LinkedHashMap<>(existing.metadata());
        int retryCount = 1;
        if (updatedMeta.containsKey("retryCount")) {
            Object countVal = updatedMeta.get("retryCount");
            if (countVal instanceof Number num) {
                retryCount = num.intValue() + 1;
            }
        }
        updatedMeta.put("retryCount", retryCount);
        if (providerRes.providerData() != null) {
            updatedMeta.putAll(providerRes.providerData());
        }

        String metaJson;
        try {
            metaJson = json.writeValueAsString(updatedMeta);
        } catch (Exception e) {
            metaJson = "{}";
        }

        Instant now = clock.instant();
        Timestamp nowTs = Timestamp.from(now);
        String targetStatus = providerRes.status() != null ? providerRes.status() : existing.status();

        // 1. Mutation
        jdbc.update(
                """
                UPDATE integration.payment_intent
                   SET status = ?,
                       provider = ?,
                       provider_ref = COALESCE(?, provider_ref),
                       qr_payload = COALESCE(?, qr_payload),
                       metadata = ?::jsonb,
                       updated_at = ?
                 WHERE payment_id = ?
                """,
                targetStatus,
                adapter.providerName(),
                providerRes.providerRef(),
                providerRes.qrPayload(),
                metaJson,
                nowTs,
                command.paymentId());

        jdbc.update(
                """
                INSERT INTO integration.payment_event_log (
                    event_id, payment_id, owner_entity_id, event_type,
                    provider, provider_ref, status, payload, created_at
                ) VALUES (?, ?, ?, 'PAYMENT_RETRIED', ?, ?, ?, ?::jsonb, ?)
                """,
                Ids.next(),
                command.paymentId(),
                existing.ownerEntityId(),
                adapter.providerName(),
                providerRes.providerRef(),
                targetStatus,
                metaJson,
                nowTs);

        // 2. Audit
        Map<String, Object> afterAudit = new LinkedHashMap<>();
        afterAudit.put("order_reference", existing.orderReference());
        afterAudit.put("amount", existing.amount());
        afterAudit.put("provider", adapter.providerName());
        afterAudit.put("retry_count", retryCount);
        audit.record(AUDIT_PAYMENT_RETRIED, Subject.of("payment_intent", command.paymentId()), null, afterAudit, scope);

        // 3. Domain Event
        events.publish(new PaymentRetried(
                command.paymentId(),
                existing.ownerEntityId(),
                existing.orderReference(),
                adapter.providerName(),
                retryCount,
                now));

        return new PaymentIntentView(
                existing.paymentId(),
                existing.ownerEntityId(),
                existing.orderReference(),
                existing.customerRef(),
                existing.amount(),
                existing.currency(),
                existing.paymentMethod(),
                targetStatus,
                adapter.providerName(),
                providerRes.providerRef() != null ? providerRes.providerRef() : existing.providerRef(),
                providerRes.qrPayload() != null ? providerRes.qrPayload() : existing.qrPayload(),
                existing.description(),
                null,
                existing.idempotencyKey(),
                updatedMeta,
                existing.initiatedAt(),
                existing.settledAt(),
                existing.createdAt(),
                now);
    }
}
