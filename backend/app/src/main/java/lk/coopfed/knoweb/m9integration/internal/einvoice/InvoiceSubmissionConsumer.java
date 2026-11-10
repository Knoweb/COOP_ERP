package lk.coopfed.knoweb.m9integration.internal.einvoice;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ACC-10: Records issued invoices for future electronic submission.
 *
 * Phase 1 uses a stub adapter only. No invoice is transmitted
 * to an external provider.
 */
@Component
public class InvoiceSubmissionConsumer {

    static final String CONSUMER = "m9.einvoice.submission";
    static final String ENABLED = "integration.einvoice.enabled";
    static final String AUDIT_PENDING = "EINVOICE_PENDING";

    private final JdbcTemplate jdbc;
    private final ConfigRegistry config;
    private final EInvoiceAdapter adapter;
    private final AuditFacade audit;

    public InvoiceSubmissionConsumer(
            JdbcTemplate jdbc, ConfigRegistry config, EInvoiceAdapter adapter, AuditFacade audit) {
        this.jdbc = jdbc;
        this.config = config;
        this.adapter = adapter;
        this.audit = audit;
    }

    @EventConsumer(types = "invoice.issued.v1", consumer = CONSUMER)
    @Transactional
    public void on(JsonNode payload, ScopeContext scope) {
        if (scope == null || scope.policyClass() != PolicyClass.OWN || scope.entityId() == null) {
            throw new ProblemException("m9.einvoice.invalid_scope");
        }

        // Disabled by default, independently for each entity.
        if (!config.getBoolean(ENABLED, scope, false)) {
            return;
        }

        UUID invoiceId = requiredUuid(payload, "invoiceId");
        UUID sellerId = requiredUuid(payload, "sellerEntityId");

        if (!sellerId.equals(scope.entityId())) {
            throw new ProblemException("m9.einvoice.owner_mismatch");
        }

        EInvoiceAdapter.InvoiceSnapshot snapshot = new EInvoiceAdapter.InvoiceSnapshot(
                invoiceId,
                sellerId,
                requiredUuid(payload, "buyerEntityId"),
                requiredText(payload, "docNumberDisplay"),
                requiredText(payload, "sellerVatNo"),
                text(payload, "buyerVatNo"),
                LocalDate.parse(requiredText(payload, "taxPointDate")),
                requiredDecimal(payload, "netAmount"),
                requiredDecimal(payload, "taxAmount"),
                requiredDecimal(payload, "grossAmount"),
                requiredText(payload, "contentHash"));

        // Claim the invoice before invoking the adapter.
        // Concurrent or repeated deliveries must not invoke it twice.
        int inserted = jdbc.update(
                """
                insert into integration.e_invoice_submission (
                    invoice_document_id,
                    owner_entity_id,
                    status,
                    attempts
                )
                values (?, ?, 'PENDING', 0)
                on conflict (invoice_document_id) do nothing
                """,
                invoiceId,
                sellerId);

        if (inserted == 0) {
            return;
        }

        EInvoiceAdapter.SubmissionResult result = adapter.submit(snapshot);

        // ACC-10 is readiness only: no external submission occurs.
        if (result == null || !"PENDING".equals(result.status())) {
            throw new ProblemException("m9.einvoice.stub_invalid");
        }

        jdbc.update(
                """
                update integration.e_invoice_submission
                   set provider_ref = ?, response_key = ?
                 where invoice_document_id = ?
                   and owner_entity_id = ?
                """,
                result.providerReference(),
                result.responseKey(),
                invoiceId,
                sellerId);

        audit.record(
                AUDIT_PENDING,
                Subject.of("invoice", invoiceId),
                null,
                Map.of("status", "PENDING", "ownerEntityId", sellerId.toString()),
                scope);
    }

    private static String text(JsonNode payload, String field) {
        if (payload == null || payload.isNull()) {
            return null;
        }
        JsonNode value = payload.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String requiredText(JsonNode payload, String field) {
        String value = text(payload, field);
        if (value == null || value.isBlank()) {
            throw new ProblemException("m9.einvoice.invoice_malformed");
        }
        return value;
    }

    private static UUID requiredUuid(JsonNode payload, String field) {
        return UUID.fromString(requiredText(payload, field));
    }

    private static BigDecimal requiredDecimal(JsonNode payload, String field) {
        return new BigDecimal(requiredText(payload, field));
    }
}
