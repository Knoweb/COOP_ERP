package lk.coopfed.knoweb.m4trading.internal.payment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentIssuance;
import lk.coopfed.knoweb.kernel.api.DocumentLinks;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.LinkType;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m4trading.api.ChequeBounced;
import lk.coopfed.knoweb.m4trading.api.ChequeCleared;
import lk.coopfed.knoweb.m4trading.api.JournalPostingsReady;
import lk.coopfed.knoweb.m4trading.api.PaymentReceiptReversed;
import lk.coopfed.knoweb.m4trading.api.Posting;
import lk.coopfed.knoweb.m4trading.api.RecordChequeOutcome;
import lk.coopfed.knoweb.m4trading.api.RecordPaymentReceipt;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingDocuments;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import lk.coopfed.knoweb.m4trading.internal.document.TradingSeries;
import lk.coopfed.knoweb.m4trading.internal.invoice.InvoiceSettlements;
import lk.coopfed.knoweb.m4trading.internal.posting.PostingMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RecordChequeOutcome (24A section 6.3). Guards, in order: the seller's entity-wide OWN scope; an
 * outcome of CLEARED or BOUNCED; a receipt the caller issued; paid by cheque; no outcome recorded
 * for it yet.
 *
 * <p>CLEARED: the seller's {@code cheque_outcome} row; audit CHEQUE_CLEARED; cheque.cleared.v1.
 *
 * <p>BOUNCED: ReversePaymentReceipt with the MFA waived, as a system consequence (24A): a PRC
 * reversal issued from the seller's series (one line, the amount negated), the REVERSES link to the
 * receipt, its {@code doc_payment_receipt} row naming the receipt, the negated {@code
 * payment_allocation} row of every invoice the receipt settled, each invoice's {@code
 * settled_amount} recomputed (the invoice reopens), and the {@code cheque_outcome} row. Audit
 * CHEQUE_BOUNCED (ALERT); events cheque.bounced.v1, payment_receipt.reversed.v1 and
 * journal.postings_ready.v1 (PRC REVERSAL). Answers the reversal's id, or null when cleared.
 */
@Service
@CommandHandler(permission = "bil.payment.record")
public class RecordChequeOutcomeHandler implements Handles<RecordChequeOutcome, UUID> {

    static final String AUDIT_CLEARED = "CHEQUE_CLEARED";
    static final String AUDIT_BOUNCED = "CHEQUE_BOUNCED";
    static final String BOUNCED_REASON = "cheque.bounced";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final DocumentIssuance issuance;
    private final DocumentLinks links;
    private final TradingSeries series;
    private final InvoiceSettlements settlements;
    private final PostingMapper postings;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    @SuppressWarnings("java:S107") // the collaborators of one handler specification
    RecordChequeOutcomeHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            DocumentIssuance issuance,
            DocumentLinks links,
            TradingSeries series,
            InvoiceSettlements settlements,
            PostingMapper postings,
            TradingClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.issuance = issuance;
        this.links = links;
        this.series = series;
        this.settlements = settlements;
        this.postings = postings;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RecordChequeOutcome command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID seller = scope.entityId();
        UUID receiptId = TradingGuards.required(command.receiptId(), "receiptId");
        String outcome = TradingGuards.required(command.outcome(), "outcome");
        if (!RecordChequeOutcome.CLEARED.equals(outcome) && !RecordChequeOutcome.BOUNCED.equals(outcome)) {
            throw new ProblemException("m4.payment.outcome_invalid", Map.of("outcome", outcome));
        }
        DocumentRecord receipt = documents
                .findById(receiptId)
                .filter(document -> RecordPaymentReceiptHandler.PRC.equals(document.docTypeCode()))
                .orElseThrow(() -> new ProblemException("m4.payment.not_found"));
        if (!seller.equals(receipt.ownerEntityId())) {
            throw new ProblemException("m4.payment.not_seller");
        }
        // Locked, so two outcomes of one cheque in flight at once see each other.
        documents.findByIdForUpdate(receiptId);
        if (jdbc.queryForObject(
                        "select count(*) from trading.cheque where receipt_document_id = ?", Integer.class, receiptId)
                == 0) {
            throw new ProblemException("m4.payment.not_cheque");
        }
        if (jdbc.queryForObject(
                        "select count(*) from trading.cheque_outcome where receipt_document_id = ?",
                        Integer.class,
                        receiptId)
                > 0) {
            throw new ProblemException("m4.payment.outcome_recorded");
        }
        UUID buyer = receipt.counterpartyEntityId();
        Map<String, Object> row = jdbc.queryForMap(
                "select relationship_id, method, received_on, amount from trading.doc_payment_receipt"
                        + " where document_id = ?",
                receiptId);
        BigDecimal amount = (BigDecimal) row.get("amount");
        String reason = command.reason() == null || command.reason().isBlank()
                ? BOUNCED_REASON
                : command.reason().strip();

        if (RecordChequeOutcome.CLEARED.equals(outcome)) {
            outcomeRow(receiptId, outcome, null, null, seller, buyer, scope);
            audit.record(
                    AUDIT_CLEARED,
                    Subject.of("payment_receipt", receiptId),
                    null,
                    Map.of("outcome", outcome, "amount", amount),
                    scope);
            events.publish(new ChequeCleared(receiptId, seller, buyer, amount));
            return null;
        }

        UUID reversalId = Ids.next();
        documents.save(TradingDocuments.draft(
                reversalId, RecordPaymentReceiptHandler.PRC, seller, buyer, null, scope.userId(), receiptId, reason));
        documents.saveLines(reversalId, List.of(RecordPaymentReceiptHandler.amountLine(reversalId, amount.negate())));
        series.ensureEntitySeries(RecordPaymentReceiptHandler.PRC, scope);
        DocumentRecord reversal = issuance.issue(
                documents.findByIdForUpdate(reversalId).orElseThrow(), documents.findLines(reversalId), scope);
        links.link(reversalId, receiptId, LinkType.REVERSES, null, scope);
        jdbc.update(
                """
                insert into trading.doc_payment_receipt (document_id, relationship_id, seller_entity_id,
                    payer_entity_id, method, reference, received_on, amount, reversal_of, reason)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                reversalId,
                row.get("relationship_id"),
                seller,
                buyer,
                row.get("method"),
                null,
                clock.today(),
                amount,
                receiptId,
                reason);
        List<RecordPaymentReceipt.Settlement> reopened = new ArrayList<>();
        for (Map<String, Object> allocation : jdbc.queryForList(
                // One row per invoice: money applied later from the account (ApplyPaymentReceipt)
                // may have settled an invoice the receipt had already part-paid.
                """
                select invoice_document_id, sum(amount) as amount from trading.payment_allocation
                 where receipt_document_id = ?
                 group by invoice_document_id order by min(allocation_id::text)
                """,
                receiptId)) {
            UUID invoiceId = (UUID) allocation.get("invoice_document_id");
            BigDecimal settled = (BigDecimal) allocation.get("amount");
            documents.lockForLinking(invoiceId);
            jdbc.update(
                    """
                    insert into trading.payment_allocation (allocation_id, receipt_document_id, invoice_document_id,
                        amount)
                    values (?, ?, ?, ?)
                    """,
                    Ids.next(),
                    reversalId,
                    invoiceId,
                    settled.negate());
            jdbc.update(
                    "update trading.doc_invoice set settled_amount = ? where document_id = ?",
                    settlements.settled(invoiceId),
                    invoiceId);
            reopened.add(new RecordPaymentReceipt.Settlement(invoiceId, settled));
        }
        outcomeRow(receiptId, outcome, reversalId, reason, seller, buyer, scope);

        List<Posting> journal =
                postings.postings(RecordPaymentReceiptHandler.PRC, "REVERSAL", "SELLER", Map.of("applied", amount));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("outcome", outcome);
        after.put("reversalId", reversalId);
        after.put("reversalNumber", reversal.docNumberDisplay());
        after.put("amount", amount);
        after.put("reopenedInvoices", reopened.size());
        after.put("reason", reason);
        audit.record(AUDIT_BOUNCED, Subject.of("payment_receipt", receiptId), null, after, scope);
        events.publish(new ChequeBounced(receiptId, reversalId, seller, buyer, amount, reason));
        events.publish(new PaymentReceiptReversed(
                reversalId, reversal.docNumberDisplay(), receiptId, seller, buyer, amount, reopened, reason));
        events.publish(new JournalPostingsReady(
                reversalId,
                RecordPaymentReceiptHandler.PRC,
                reversal.docNumberDisplay(),
                seller,
                journal,
                reversal.businessDate(),
                null));
        return reversalId;
    }

    private void outcomeRow(
            UUID receiptId,
            String outcome,
            UUID reversalId,
            String reason,
            UUID seller,
            UUID buyer,
            ScopeContext scope) {
        jdbc.update(
                """
                insert into trading.cheque_outcome (receipt_document_id, outcome, reversal_document_id, reason,
                    recorded_by, recorded_at, owner_entity_id, counterparty_entity_id)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                receiptId,
                outcome,
                reversalId,
                reason,
                scope.userId(),
                java.sql.Timestamp.from(clock.now()),
                seller,
                buyer);
    }
}
