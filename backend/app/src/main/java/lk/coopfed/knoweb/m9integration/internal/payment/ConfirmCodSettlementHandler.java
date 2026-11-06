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
import lk.coopfed.knoweb.m9integration.api.ConfirmCodSettlement;
import lk.coopfed.knoweb.m9integration.api.PaymentSettled;
import lk.coopfed.knoweb.m9integration.query.PaymentIntentView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles confirmation and settlement of Cash on Delivery (COD) payment collection (M3-07).
 */
@Service
@CommandHandler(permission = "int.payment.manage")
public class ConfirmCodSettlementHandler implements Handles<ConfirmCodSettlement, PaymentIntentView> {

    public static final String AUDIT_COD_COLLECTED = "COD_PAYMENT_COLLECTED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;
    private final ObjectMapper json;
    private final PaymentRowMapper rowMapper;

    public ConfirmCodSettlementHandler(
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
    public PaymentIntentView handle(ConfirmCodSettlement command, ScopeContext scope) {
        if (command.paymentId() == null) {
            throw new ProblemException("m9.payment.not_found");
        }

        List<PaymentIntentView> list = jdbc.query(
                "SELECT * FROM integration.payment_intent WHERE payment_id = ?", rowMapper, command.paymentId());

        if (list.isEmpty()) {
            throw new ProblemException("m9.payment.not_found");
        }

        PaymentIntentView existing = list.get(0);

        if (!"COD".equalsIgnoreCase(existing.paymentMethod())) {
            throw new ProblemException("m9.payment.not_cod");
        }

        // Idempotency: If already settled/paid, return without duplicate execution
        if ("PAID".equalsIgnoreCase(existing.status())) {
            return existing;
        }

        if ("CANCELLED".equalsIgnoreCase(existing.status())) {
            throw new ProblemException("m9.payment.already_cancelled");
        }

        Instant now = clock.instant();
        Timestamp nowTs = Timestamp.from(now);

        Map<String, Object> updatedMeta = new LinkedHashMap<>(existing.metadata());
        if (command.receiptReference() != null) {
            updatedMeta.put("receiptReference", command.receiptReference());
        }
        if (command.metadata() != null) {
            updatedMeta.putAll(command.metadata());
        }

        String metaJson;
        try {
            metaJson = json.writeValueAsString(updatedMeta);
        } catch (Exception e) {
            metaJson = "{}";
        }

        String receiptRef = command.receiptReference() != null ? command.receiptReference() : existing.providerRef();

        // 1. Mutation
        jdbc.update(
                """
                UPDATE integration.payment_intent
                   SET status = 'PAID',
                       provider_ref = COALESCE(?, provider_ref),
                       settled_at = ?,
                       metadata = ?::jsonb,
                       updated_at = ?
                 WHERE payment_id = ?
                """,
                receiptRef,
                nowTs,
                metaJson,
                nowTs,
                command.paymentId());

        jdbc.update(
                """
                INSERT INTO integration.payment_event_log (
                    event_id, payment_id, owner_entity_id, event_type,
                    provider, provider_ref, status, payload, created_at
                ) VALUES (?, ?, ?, 'COD_PAYMENT_COLLECTED', ?, ?, 'PAID', ?::jsonb, ?)
                """,
                Ids.next(),
                command.paymentId(),
                existing.ownerEntityId(),
                existing.provider(),
                receiptRef,
                metaJson,
                nowTs);

        // 2. Audit
        Map<String, Object> afterAudit = new LinkedHashMap<>();
        afterAudit.put("order_reference", existing.orderReference());
        afterAudit.put("amount", existing.amount());
        afterAudit.put("receipt_reference", receiptRef != null ? receiptRef : "");
        audit.record(AUDIT_COD_COLLECTED, Subject.of("payment_intent", command.paymentId()), null, afterAudit, scope);

        // 3. Domain Event
        events.publish(new PaymentSettled(
                command.paymentId(),
                existing.ownerEntityId(),
                existing.orderReference(),
                "COD",
                existing.amount(),
                existing.provider(),
                receiptRef,
                now));

        return new PaymentIntentView(
                existing.paymentId(),
                existing.ownerEntityId(),
                existing.orderReference(),
                existing.customerRef(),
                existing.amount(),
                existing.currency(),
                "COD",
                "PAID",
                existing.provider(),
                receiptRef,
                existing.qrPayload(),
                existing.description(),
                null,
                existing.idempotencyKey(),
                updatedMeta,
                existing.initiatedAt(),
                now,
                existing.createdAt(),
                now);
    }
}
