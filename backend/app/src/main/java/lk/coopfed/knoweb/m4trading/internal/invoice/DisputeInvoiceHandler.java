package lk.coopfed.knoweb.m4trading.internal.invoice;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m4trading.api.DisputeInvoice;
import lk.coopfed.knoweb.m4trading.api.InvoiceDisputed;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DisputeInvoice (24A section 6). Guards, in order: the buyer's entity-wide OWN scope; an invoice
 * and a reason; an issued invoice the caller received (the buyer); not disputed already (an
 * advisory lock per invoice serialises two acts on its dispute). Mutation: a DISPUTED row of the
 * buyer's own in {@code trading.invoice_dispute} (the invoice is the seller's document and is not
 * written, AGENTS.md idea 3). Audit INVOICE_DISPUTED; event invoice.disputed.v1.
 */
@Service
@CommandHandler(permission = "bil.invoice.dispute")
public class DisputeInvoiceHandler implements Handles<DisputeInvoice, Void> {

    static final String AUDIT_DISPUTED = "INVOICE_DISPUTED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final InvoiceDisputes disputes;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    DisputeInvoiceHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            InvoiceDisputes disputes,
            TradingClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.disputes = disputes;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Void handle(DisputeInvoice command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID invoiceId = TradingGuards.required(command.invoiceId(), "invoiceId");
        String reason = TradingGuards.required(command.reason(), "reason").strip();
        DocumentRecord invoice = documents
                .findById(invoiceId)
                .filter(document -> IssueInvoiceHandler.INV.equals(document.docTypeCode()))
                .filter(DocumentRecord::isIssued)
                .orElseThrow(() -> new ProblemException("m4.invoice.not_found"));
        if (!scope.entityId().equals(invoice.counterpartyEntityId())) {
            throw new ProblemException("m4.invoice.not_buyer");
        }
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?::text))", "invoice-dispute-" + invoiceId);
        if (disputes.isDisputed(invoiceId)) {
            throw new ProblemException("m4.invoice.disputed_already");
        }

        jdbc.update(
                """
                insert into trading.invoice_dispute (dispute_event_id, invoice_document_id, action, reason,
                    actor_user_id, recorded_at, owner_entity_id, counterparty_entity_id)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                Ids.next(),
                invoiceId,
                InvoiceDisputes.DISPUTED,
                reason,
                scope.userId(),
                Timestamp.from(clock.now()),
                scope.entityId(),
                invoice.ownerEntityId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("dispute", InvoiceDisputes.DISPUTED);
        after.put("reason", reason);
        audit.record(AUDIT_DISPUTED, Subject.of("invoice", invoiceId), null, after, scope);
        events.publish(new InvoiceDisputed(invoiceId, invoice.ownerEntityId(), scope.entityId(), reason));
        return null;
    }
}
