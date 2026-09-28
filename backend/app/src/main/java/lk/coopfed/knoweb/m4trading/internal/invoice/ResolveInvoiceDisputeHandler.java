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
import lk.coopfed.knoweb.m4trading.api.InvoiceDisputeResolved;
import lk.coopfed.knoweb.m4trading.api.ResolveInvoiceDispute;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ResolveInvoiceDispute (24A section 6: "seller+buyer or arbiter"). Guards, in order: an
 * entity-wide OWN scope; an invoice; an issued invoice of which the caller is the seller or the
 * buyer; disputed now (an advisory lock per invoice). Mutation: a RESOLVED row of the caller's own
 * in {@code trading.invoice_dispute}, with the note if any. Audit INVOICE_DISPUTE_RESOLVED; event
 * invoice.dispute_resolved.v1. The arbiter's path is deferred with arbitration.
 *
 * <p>Decided on the architect's delegation: one permission, {@code bil.invoice.dispute}, for either
 * party (an operation carries one x-permission); a seller's role that resolves disputes holds it.
 */
@Service
@CommandHandler(permission = "bil.invoice.dispute")
public class ResolveInvoiceDisputeHandler implements Handles<ResolveInvoiceDispute, Void> {

    static final String AUDIT_RESOLVED = "INVOICE_DISPUTE_RESOLVED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final InvoiceDisputes disputes;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    ResolveInvoiceDisputeHandler(
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
    public Void handle(ResolveInvoiceDispute command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID invoiceId = TradingGuards.required(command.invoiceId(), "invoiceId");
        DocumentRecord invoice = documents
                .findById(invoiceId)
                .filter(document -> IssueInvoiceHandler.INV.equals(document.docTypeCode()))
                .filter(DocumentRecord::isIssued)
                .orElseThrow(() -> new ProblemException("m4.invoice.not_found"));
        UUID caller = scope.entityId();
        UUID other;
        if (caller.equals(invoice.ownerEntityId())) {
            other = invoice.counterpartyEntityId();
        } else if (caller.equals(invoice.counterpartyEntityId())) {
            other = invoice.ownerEntityId();
        } else {
            throw new ProblemException("m4.invoice.not_found");
        }
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?::text))", "invoice-dispute-" + invoiceId);
        if (!disputes.isDisputed(invoiceId)) {
            throw new ProblemException("m4.invoice.not_disputed");
        }
        String note = command.note() == null || command.note().isBlank()
                ? null
                : command.note().strip();

        jdbc.update(
                """
                insert into trading.invoice_dispute (dispute_event_id, invoice_document_id, action, reason,
                    actor_user_id, recorded_at, owner_entity_id, counterparty_entity_id)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                Ids.next(),
                invoiceId,
                InvoiceDisputes.RESOLVED,
                note,
                scope.userId(),
                Timestamp.from(clock.now()),
                caller,
                other);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("dispute", InvoiceDisputes.RESOLVED);
        if (note != null) {
            after.put("note", note);
        }
        audit.record(
                AUDIT_RESOLVED,
                Subject.of("invoice", invoiceId),
                Map.of("dispute", InvoiceDisputes.DISPUTED),
                after,
                scope);
        events.publish(
                new InvoiceDisputeResolved(invoiceId, invoice.ownerEntityId(), invoice.counterpartyEntityId(), caller));
        return null;
    }
}
