package lk.coopfed.knoweb.m9integration.internal.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m9integration.api.InitiatePayment;
import lk.coopfed.knoweb.m9integration.api.PaymentCodPending;
import lk.coopfed.knoweb.m9integration.api.PaymentInitiated;
import lk.coopfed.knoweb.m9integration.internal.payment.adapter.PaymentAdapterRegistry;
import lk.coopfed.knoweb.m9integration.internal.payment.adapter.PaymentProviderAdapter;
import lk.coopfed.knoweb.m9integration.internal.payment.adapter.PaymentProviderAdapter.ProviderInitiationRequest;
import lk.coopfed.knoweb.m9integration.internal.payment.adapter.PaymentProviderAdapter.ProviderInitiationResult;
import lk.coopfed.knoweb.m9integration.query.PaymentIntentView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles initiation of an online payment, dynamic LankaQR generation, or COD intent (M3-07).
 */
@Service
@CommandHandler(permission = "int.payment.initiate")
public class InitiatePaymentHandler implements Handles<InitiatePayment, PaymentIntentView> {

    public static final String AUDIT_PAYMENT_INITIATED = "PAYMENT_INITIATED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;
    private final PaymentAdapterRegistry adapterRegistry;
    private final ObjectMapper json;
    private final PaymentRowMapper rowMapper;

    public InitiatePaymentHandler(
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
    public PaymentIntentView handle(InitiatePayment command, ScopeContext scope) {
        if (command.amount() == null || command.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ProblemException("m9.payment.amount_positive");
        }
        if (command.orderReference() == null || command.orderReference().isBlank()) {
            throw new ProblemException("m9.payment.order_reference_required");
        }
        if (command.customerRef() == null || command.customerRef().isBlank()) {
            throw new ProblemException("m9.payment.customer_ref_required");
        }
        if (command.paymentMethod() == null || command.paymentMethod().isBlank()) {
            throw new ProblemException("m9.payment.payment_method_required");
        }

        String method = command.paymentMethod().toUpperCase();
        if (!"ONLINE".equals(method) && !"LANKA_QR".equals(method) && !"COD".equals(method)) {
            throw new ProblemException("m9.payment.payment_method_invalid");
        }

        UUID ownerEntityId = scope != null && scope.entityId() != null
                ? scope.entityId()
                : UUID.fromString("0190e9f0-0000-7000-8000-000000000001");

        // Idempotency check: if already initiated under this dedup/idempotency key, return existing intent
        String idempotencyKey =
                command.idempotencyKey() != null && !command.idempotencyKey().isBlank()
                        ? command.idempotencyKey().trim()
                        : command.orderReference() + "-" + method;

        List<PaymentIntentView> existingList = jdbc.query(
                "SELECT * FROM integration.payment_intent WHERE owner_entity_id = ? AND idempotency_key = ?",
                rowMapper,
                ownerEntityId,
                idempotencyKey);

        if (!existingList.isEmpty()) {
            return existingList.get(0);
        }

        UUID paymentId = command.paymentId() != null ? command.paymentId() : Ids.next();
        PaymentProviderAdapter adapter = adapterRegistry.resolve(command.provider(), method);

        ProviderInitiationRequest providerReq = new ProviderInitiationRequest(
                paymentId,
                command.orderReference(),
                command.customerRef(),
                command.amount(),
                "LKR",
                method,
                command.description(),
                command.metadata());

        ProviderInitiationResult providerRes = adapter.initiate(providerReq);

        Map<String, Object> mergedMeta = new LinkedHashMap<>(command.metadata());
        if (providerRes.providerData() != null) {
            mergedMeta.putAll(providerRes.providerData());
        }
        if (providerRes.redirectUrl() != null) {
            mergedMeta.put("redirectUrl", providerRes.redirectUrl());
        }

        String metaJson;
        try {
            metaJson = json.writeValueAsString(mergedMeta);
        } catch (Exception e) {
            metaJson = "{}";
        }

        Instant now = clock.instant();
        Timestamp nowTs = Timestamp.from(now);

        String initialStatus = providerRes.status() != null
                ? providerRes.status()
                : ("COD".equals(method) ? "COD_PENDING" : "PENDING");

        // 1. Mutation
        jdbc.update(
                """
                INSERT INTO integration.payment_intent (
                    payment_id, owner_entity_id, order_reference, customer_ref,
                    amount, currency, payment_method, status, provider,
                    provider_ref, qr_payload, description, idempotency_key,
                    metadata, initiated_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'LKR', ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """,
                paymentId,
                ownerEntityId,
                command.orderReference().trim(),
                command.customerRef().trim(),
                command.amount(),
                method,
                initialStatus,
                adapter.providerName(),
                providerRes.providerRef(),
                providerRes.qrPayload(),
                command.description(),
                idempotencyKey,
                metaJson,
                nowTs,
                nowTs,
                nowTs);

        // Record in payment event log
        jdbc.update(
                """
                INSERT INTO integration.payment_event_log (
                    event_id, payment_id, owner_entity_id, event_type,
                    provider, provider_ref, status, payload, created_at
                ) VALUES (?, ?, ?, 'PAYMENT_INITIATED', ?, ?, ?, ?::jsonb, ?)
                """,
                Ids.next(),
                paymentId,
                ownerEntityId,
                adapter.providerName(),
                providerRes.providerRef(),
                initialStatus,
                metaJson,
                nowTs);

        // 2. Audit (Strict zero-PII: orderReference, amount, method, provider only)
        Map<String, Object> afterAudit = new LinkedHashMap<>();
        afterAudit.put("order_reference", command.orderReference());
        afterAudit.put("amount", command.amount());
        afterAudit.put("payment_method", method);
        afterAudit.put("provider", adapter.providerName());
        audit.record(AUDIT_PAYMENT_INITIATED, Subject.of("payment_intent", paymentId), null, afterAudit, scope);

        // 3. Domain Event
        if ("COD".equals(method)) {
            events.publish(
                    new PaymentCodPending(paymentId, ownerEntityId, command.orderReference(), command.amount(), now));
        } else {
            events.publish(new PaymentInitiated(
                    paymentId,
                    ownerEntityId,
                    command.orderReference(),
                    command.customerRef(),
                    method,
                    command.amount(),
                    initialStatus,
                    adapter.providerName(),
                    providerRes.providerRef(),
                    providerRes.qrPayload(),
                    now));
        }

        return new PaymentIntentView(
                paymentId,
                ownerEntityId,
                command.orderReference(),
                command.customerRef(),
                command.amount(),
                "LKR",
                method,
                initialStatus,
                adapter.providerName(),
                providerRes.providerRef(),
                providerRes.qrPayload(),
                command.description(),
                null,
                idempotencyKey,
                mergedMeta,
                now,
                null,
                now,
                now);
    }
}
