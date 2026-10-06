package lk.coopfed.knoweb.m4trading.internal.invoice;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentLinks;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.LinkType;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m4trading.api.ApplyCreditNote;
import lk.coopfed.knoweb.m4trading.api.CreditNoteApplied;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ApplyCreditNote (24A section 6; decision B-1 of {@code
 * docs/progress/deviations/2026-10-06-wave2-credit-once-per-line.md}, CR-24A-3 item 2). A credit
 * note is issued for the goods in full, and its money applies to the invoice it credits only as
 * far as that invoice was still due; the rest is its unapplied amount, {@code gross - Σ its
 * CREDITS links}, never stored. The seller's accounts apply it here to another open, undisputed
 * invoice of the same buyer (or to the same invoice, once a bounced cheque reopened it).
 *
 * <p>Guards, in order: the seller's entity-wide OWN scope; a credit note and an invoice; the
 * caller's own credit note ({@code m4.creditnote.not_found}, {@code m4.creditnote.not_seller}); an
 * issued invoice ({@code m4.invoice.not_found}, {@code m4.invoice.not_issued}) of the same seller
 * and buyer ({@code m4.creditnote.invoice_not_ours}), not disputed ({@code
 * m4.creditnote.invoice_disputed}), that the credit note has not been applied to before ({@code
 * m4.creditnote.applied_already}: the kernel keys a link on its two documents and its type); then,
 * under the credit note's lock and the invoice's, an amount above zero to the cent ({@code
 * m4.creditnote.amount_invalid}), no more than the credit note holds unapplied ({@code
 * m4.creditnote.exceeds_unapplied}) and no more than is due on the invoice ({@code
 * m4.creditnote.exceeds_due}).
 *
 * <p>Mutation: a CREDITS link from the credit note to the invoice with the amount (the kernel
 * checks the invoice's open balance as well), and that invoice's {@code credited_amount} cache
 * recomputed from its CREDITS links. No document is issued and no journal is posted: the credit
 * note's own posting already credited the buyer's account with its whole amount; this only says
 * which invoice it reduces. Nothing is refunded (ADR-01). Audit CREDIT_NOTE_APPLIED; event
 * credit_note.applied.v1.
 */
@Service
@CommandHandler(permission = "bil.creditnote.issue")
public class ApplyCreditNoteHandler implements Handles<ApplyCreditNote, BigDecimal> {

    static final String AUDIT_APPLIED = "CREDIT_NOTE_APPLIED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final DocumentLinks links;
    private final InvoiceSettlements settlements;
    private final InvoiceDisputes disputes;
    private final AuditFacade audit;
    private final EventPublisher events;

    @SuppressWarnings("java:S107") // the collaborators of one handler specification
    ApplyCreditNoteHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            DocumentLinks links,
            InvoiceSettlements settlements,
            InvoiceDisputes disputes,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.links = links;
        this.settlements = settlements;
        this.disputes = disputes;
        this.audit = audit;
        this.events = events;
    }

    /** @return the amount applied */
    @Override
    @Transactional
    public BigDecimal handle(ApplyCreditNote command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID seller = scope.entityId();
        UUID creditNoteId = TradingGuards.required(command.creditNoteId(), "creditNoteId");
        UUID invoiceId = TradingGuards.required(command.invoiceId(), "invoiceId");
        DocumentRecord creditNote = documents
                .findById(creditNoteId)
                .filter(document -> IssueCreditNoteHandler.CN.equals(document.docTypeCode()))
                .orElseThrow(() -> new ProblemException("m4.creditnote.not_found"));
        if (!seller.equals(creditNote.ownerEntityId())) {
            throw new ProblemException("m4.creditnote.not_seller");
        }
        UUID buyer = creditNote.counterpartyEntityId();
        DocumentRecord invoice = documents
                .findById(invoiceId)
                .filter(document -> IssueInvoiceHandler.INV.equals(document.docTypeCode()))
                .orElseThrow(() -> new ProblemException("m4.invoice.not_found"));
        if (!seller.equals(invoice.ownerEntityId()) || !buyer.equals(invoice.counterpartyEntityId())) {
            throw new ProblemException("m4.creditnote.invoice_not_ours", Map.of("invoiceId", invoiceId));
        }
        if (!invoice.isIssued()) {
            throw new ProblemException("m4.invoice.not_issued");
        }
        if (disputes.isDisputed(invoiceId)) {
            throw new ProblemException("m4.creditnote.invoice_disputed", Map.of("invoiceId", invoiceId));
        }

        // The credit note first, then the invoice, always in this order: two applications of one
        // credit note in flight at once see each other's link, and a credit path that locks the
        // invoice never holds a credit note's lock.
        documents.lockForLinking(creditNoteId);
        documents.lockForLinking(invoiceId);
        boolean appliedThere = documents.findLinks(creditNoteId).stream()
                .anyMatch(link -> link.linkType() == LinkType.CREDITS
                        && creditNoteId.equals(link.fromDocumentId())
                        && invoiceId.equals(link.toDocumentId()));
        if (appliedThere) {
            throw new ProblemException("m4.creditnote.applied_already", Map.of("invoiceId", invoiceId));
        }
        BigDecimal unapplied = settlements.unappliedOf(creditNoteId);
        BigDecimal due = settlements.amountDue(invoiceId);
        BigDecimal amount = command.amount() == null ? unapplied.min(due) : command.amount();
        if (command.amount() != null
                && (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2)) {
            throw new ProblemException("m4.creditnote.amount_invalid");
        }
        if (unapplied.signum() <= 0 || amount.compareTo(unapplied) > 0) {
            throw new ProblemException(
                    "m4.creditnote.exceeds_unapplied",
                    Map.of("unapplied", unapplied.max(BigDecimal.ZERO).toPlainString()));
        }
        if (due.signum() <= 0 || amount.compareTo(due) > 0) {
            throw new ProblemException(
                    "m4.creditnote.exceeds_due",
                    Map.of("due", due.max(BigDecimal.ZERO).toPlainString(), "amount", amount.toPlainString()));
        }

        links.link(creditNoteId, invoiceId, LinkType.CREDITS, amount, scope);
        BigDecimal credited = IssueCreditNoteHandler.creditedFromLinks(documents, invoiceId);
        jdbc.update("update trading.doc_invoice set credited_amount = ? where document_id = ?", credited, invoiceId);
        BigDecimal left = unapplied.subtract(amount);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("unappliedAmount", unapplied);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("docNumber", creditNote.docNumberDisplay());
        after.put("invoiceId", invoiceId);
        after.put("appliedAmount", amount);
        after.put("unappliedAmount", left);
        after.put("invoiceCreditedAmount", credited);
        audit.record(AUDIT_APPLIED, Subject.of("credit_note", creditNoteId), before, after, scope);
        events.publish(new CreditNoteApplied(
                creditNoteId, creditNote.docNumberDisplay(), invoiceId, seller, buyer, amount, left));
        return amount;
    }
}
