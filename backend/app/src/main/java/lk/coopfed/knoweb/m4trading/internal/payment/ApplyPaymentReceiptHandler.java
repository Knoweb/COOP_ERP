package lk.coopfed.knoweb.m4trading.internal.payment;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
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
import lk.coopfed.knoweb.m4trading.api.ApplyPaymentReceipt;
import lk.coopfed.knoweb.m4trading.api.PaymentReceiptApplied;
import lk.coopfed.knoweb.m4trading.api.RecordPaymentReceipt;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import lk.coopfed.knoweb.m4trading.internal.invoice.InvoiceSettlements;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ApplyReceipt (24A section 6.3): money a receipt left on the buyer's account settles invoices
 * issued since. Guards, in order: the seller's entity-wide OWN scope; a PRC the caller can see
 * ({@code m4.payment.not_found}); the caller's own ({@code m4.payment.not_seller}); the receipt
 * locked, then RECORDED, neither a reversal nor reversed ({@code m4.payment.not_recorded});
 * something on account ({@code m4.payment.nothing_on_account}); the chosen settlements checked as
 * RecordPaymentReceipt checks them, together no more than what is on account
 * ({@code m4.payment.exceeds_on_account}), or, none chosen, the open undisputed invoices oldest
 * first; at least one invoice settled ({@code m4.payment.nothing_to_apply}).
 *
 * <p>Mutation: one {@code payment_allocation} row per invoice, of the receipt, carrying the
 * application's id (V0007), and each invoice's {@code settled_amount} cache recomputed. No
 * document is issued and no journal is posted: the receipt's posting already credited the
 * buyer's account with the whole amount; this only says which invoices it pays. The rows being
 * the receipt's, a bounced cheque's reversal negates them with the rest (RecordChequeOutcome),
 * which is how an application is reversed (24A: ReversePaymentReceipt).
 *
 * <p>Audit PAYMENT_RECEIPT_APPLIED; event payment_receipt.applied.v1.
 */
@Service
@CommandHandler(permission = "bil.payment.record")
public class ApplyPaymentReceiptHandler implements Handles<ApplyPaymentReceipt, UUID> {

    static final String AUDIT_APPLIED = "PAYMENT_RECEIPT_APPLIED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final SettlementPlanner planner;
    private final InvoiceSettlements settlements;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    ApplyPaymentReceiptHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            SettlementPlanner planner,
            InvoiceSettlements settlements,
            TradingClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.planner = planner;
        this.settlements = settlements;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ApplyPaymentReceipt command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID seller = scope.entityId();
        UUID receiptId = TradingGuards.required(command.receiptId(), "receiptId");
        DocumentRecord receipt = documents
                .findById(receiptId)
                .filter(document -> RecordPaymentReceiptHandler.PRC.equals(document.docTypeCode()))
                .orElseThrow(() -> new ProblemException("m4.payment.not_found"));
        if (!seller.equals(receipt.ownerEntityId())) {
            throw new ProblemException("m4.payment.not_seller");
        }
        // Locked, so a bounce and an application of one receipt in flight at once see each other.
        documents.findByIdForUpdate(receiptId);
        Map<String, Object> row = jdbc.queryForMap(
                """
                select r.relationship_id, r.payer_entity_id, r.amount, r.reversal_of,
                       (select count(*) from trading.doc_payment_receipt x where x.reversal_of = r.document_id)
                           as reversals
                  from trading.doc_payment_receipt r where r.document_id = ?
                """,
                receiptId);
        if (row.get("reversal_of") != null || ((Number) row.get("reversals")).intValue() > 0) {
            throw new ProblemException("m4.payment.not_recorded");
        }
        UUID buyer = (UUID) row.get("payer_entity_id");
        BigDecimal amount = (BigDecimal) row.get("amount");
        BigDecimal onAccount = amount.subtract(allocated(receiptId));
        if (onAccount.signum() <= 0) {
            throw new ProblemException("m4.payment.nothing_on_account");
        }
        List<RecordPaymentReceipt.Settlement> applied = command.settlements().isEmpty()
                ? planner.oldestFirst(seller, buyer, onAccount)
                : planner.chosen(command.settlements(), seller, buyer, onAccount, "m4.payment.exceeds_on_account");
        if (applied.isEmpty()) {
            throw new ProblemException("m4.payment.nothing_to_apply");
        }
        BigDecimal sum =
                applied.stream().map(RecordPaymentReceipt.Settlement::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        UUID applicationId = Ids.next();
        for (RecordPaymentReceipt.Settlement settlement : applied) {
            jdbc.update(
                    """
                    insert into trading.payment_allocation (allocation_id, receipt_document_id, invoice_document_id,
                        amount, application_id)
                    values (?, ?, ?, ?, ?)
                    """,
                    Ids.next(),
                    receiptId,
                    settlement.invoiceId(),
                    settlement.amount(),
                    applicationId);
            jdbc.update(
                    "update trading.doc_invoice set settled_amount = ? where document_id = ?",
                    settlements.settled(settlement.invoiceId()),
                    settlement.invoiceId());
        }
        BigDecimal left = onAccount.subtract(sum);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("unapplied", onAccount);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("applicationId", applicationId);
        after.put("docNumber", receipt.docNumberDisplay());
        after.put("applied", sum);
        after.put("invoices", applied.size());
        after.put("unapplied", left);
        audit.record(AUDIT_APPLIED, Subject.of("payment_receipt", receiptId), before, after, scope);
        events.publish(new PaymentReceiptApplied(
                applicationId,
                receiptId,
                receipt.docNumberDisplay(),
                (UUID) row.get("relationship_id"),
                seller,
                buyer,
                clock.today(),
                sum,
                applied,
                left));
        return applicationId;
    }

    private BigDecimal allocated(UUID receiptId) {
        BigDecimal sum = jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from trading.payment_allocation where receipt_document_id = ?",
                BigDecimal.class,
                receiptId);
        return sum == null ? BigDecimal.ZERO : sum;
    }
}
