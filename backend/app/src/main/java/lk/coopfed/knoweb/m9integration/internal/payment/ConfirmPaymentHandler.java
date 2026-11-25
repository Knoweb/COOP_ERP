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
import lk.coopfed.knoweb.m9integration.api.ConfirmPayment;
import lk.coopfed.knoweb.m9integration.api.PaymentFailed;
import lk.coopfed.knoweb.m9integration.api.PaymentSettled;
import lk.coopfed.knoweb.m9integration.query.PaymentIntentView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles confirmation and settlement of a payment transaction from provider callback or webhook (M3-07).
 */
@Service
@CommandHandler(permission = "int.payment.manage")
public class ConfirmPaymentHandler implements Handles<ConfirmPayment, PaymentIntentView> {

    public static final String AUDIT_PAYMENT_SETTLED = "PAYMENT_SETTLED";
    public static final String AUDIT_PAYMENT_FAILED = "PAYMENT_FAILED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;
    private final ObjectMapper json;
    private final PaymentRowMapper rowMapper;

    public ConfirmPaymentHandler(
            JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, Clock clock, ObjectMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.json = json != null ? json : new ObjectMapper();
        this.rowMapper = new PaymentRowMapper(this.json);
    }

    @Override
    @Transactional
    public PaymentIntentView handle(ConfirmPayment command, ScopeContext scope) {
        if (command.paymentId() == null) {
            throw new ProblemException("m9.payment.not_found");
        }

        List<PaymentIntentView> list = jdbc.query(
                "SELECT * FROM integration.payment_intent WHERE payment_id = ?", rowMapper, command.paymentId());

        if (list.isEmpty()) {
            throw new ProblemException("m9.payment.not_found");
        }

        PaymentIntentView existing = list.get(0);

        // Idempotency: If already settled/paid, return existing intent without duplicate settlement
        if ("PAID".equalsIgnoreCase(existing.status())) {
            return existing;
        }

        if ("CANCELLED".equalsIgnoreCase(existing.status())) {
            throw new ProblemException("m9.payment.already_cancelled");
        }

        String targetStatus = command.status() != null && !command.status().isBlank()
                ? command.status().toUpperCase()
                : "PAID";

        Instant now = clock.instant();
        Timestamp nowTs = Timestamp.from(now);
        String updatedProviderRef =
                command.providerRef() != null && !command.providerRef().isBlank()
                        ? command.providerRef()
                        : existing.providerRef();

        Map<String, Object> updatedMeta = new LinkedHashMap<>(existing.metadata());
        if (command.metadata() != null) {
            updatedMeta.putAll(command.metadata());
        }

        String metaJson;
        try {
            metaJson = json.writeValueAsString(updatedMeta);
        } catch (Exception e) {
            metaJson = "{}";
        }

        // 1. Mutation
        if ("PAID".equals(targetStatus)) {
            jdbc.update(
                    """
                    UPDATE integration.payment_intent
                       SET status = 'PAID',
                           provider_ref = ?,
                           settled_at = ?,
                           metadata = ?::jsonb,
                           updated_at = ?
                     WHERE payment_id = ?
                    """,
                    updatedProviderRef,
                    nowTs,
                    metaJson,
                    nowTs,
                    command.paymentId());

            jdbc.update(
                    """
                    INSERT INTO integration.payment_event_log (
                        event_id, payment_id, owner_entity_id, event_type,
                        provider, provider_ref, status, payload, created_at
                    ) VALUES (?, ?, ?, 'PAYMENT_SETTLED', ?, ?, 'PAID', ?::jsonb, ?)
                    """,
                    Ids.next(),
                    command.paymentId(),
                    existing.ownerEntityId(),
                    existing.provider(),
                    updatedProviderRef,
                    metaJson,
                    nowTs);

            // 2. Audit
            Map<String, Object> afterAudit = new LinkedHashMap<>();
            afterAudit.put("order_reference", existing.orderReference());
            afterAudit.put("amount", existing.amount());
            afterAudit.put("provider", existing.provider());
            audit.record(
                    AUDIT_PAYMENT_SETTLED, Subject.of("payment_intent", command.paymentId()), null, afterAudit, scope);

            // 3. Domain Event
            events.publish(new PaymentSettled(
                    command.paymentId(),
                    existing.ownerEntityId(),
                    existing.orderReference(),
                    existing.paymentMethod(),
                    existing.amount(),
                    existing.provider(),
                    updatedProviderRef,
                    now));

            return new PaymentIntentView(
                    existing.paymentId(),
                    existing.ownerEntityId(),
                    existing.orderReference(),
                    existing.customerRef(),
                    existing.amount(),
                    existing.currency(),
                    existing.paymentMethod(),
                    "PAID",
                    existing.provider(),
                    updatedProviderRef,
                    existing.qrPayload(),
                    existing.description(),
                    null,
                    existing.idempotencyKey(),
                    updatedMeta,
                    existing.initiatedAt(),
                    now,
                    existing.createdAt(),
                    now);
        } else {
            // Failed status
            String reason = command.failureReason() != null ? command.failureReason() : "Payment rejected or failed";
            jdbc.update(
                    """
                    UPDATE integration.payment_intent
                       SET status = 'FAILED',
                           provider_ref = ?,
                           failure_reason = ?,
                           metadata = ?::jsonb,
                           updated_at = ?
                     WHERE payment_id = ?
                    """,
                    updatedProviderRef,
                    reason,
                    metaJson,
                    nowTs,
                    command.paymentId());

            jdbc.update(
                    """
                    INSERT INTO integration.payment_event_log (
                        event_id, payment_id, owner_entity_id, event_type,
                        provider, provider_ref, status, payload, created_at
                    ) VALUES (?, ?, ?, 'PAYMENT_FAILED', ?, ?, 'FAILED', ?::jsonb, ?)
                    """,
                    Ids.next(),
                    command.paymentId(),
                    existing.ownerEntityId(),
                    existing.provider(),
                    updatedProviderRef,
                    metaJson,
                    nowTs);

            // Audit
            Map<String, Object> afterAudit = new LinkedHashMap<>();
            afterAudit.put("order_reference", existing.orderReference());
            afterAudit.put("amount", existing.amount());
            afterAudit.put("failure_reason", reason);
            audit.record(
                    AUDIT_PAYMENT_FAILED, Subject.of("payment_intent", command.paymentId()), null, afterAudit, scope);

            // Domain Event
            events.publish(new PaymentFailed(
                    command.paymentId(),
                    existing.ownerEntityId(),
                    existing.orderReference(),
                    existing.paymentMethod(),
                    existing.amount(),
                    existing.provider(),
                    reason,
                    now));

            return new PaymentIntentView(
                    existing.paymentId(),
                    existing.ownerEntityId(),
                    existing.orderReference(),
                    existing.customerRef(),
                    existing.amount(),
                    existing.currency(),
                    existing.paymentMethod(),
                    "FAILED",
                    existing.provider(),
                    updatedProviderRef,
                    existing.qrPayload(),
                    existing.description(),
                    reason,
                    existing.idempotencyKey(),
                    updatedMeta,
                    existing.initiatedAt(),
                    null,
                    existing.createdAt(),
                    now);
        }
    }
}
