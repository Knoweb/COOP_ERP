package lk.coopfed.knoweb.m4trading.internal.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentIssuance;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.RelationshipQueries;
import lk.coopfed.knoweb.m1party.query.RelationshipView;
import lk.coopfed.knoweb.m4trading.api.JournalPostingsReady;
import lk.coopfed.knoweb.m4trading.api.PaymentReceiptRecorded;
import lk.coopfed.knoweb.m4trading.api.Posting;
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
 * RecordPaymentReceipt (24A section 6.3). Guards, in order: the seller's entity-wide OWN scope; a
 * buyer, a known method, an amount above zero, the date received not in the future; a cheque's
 * bank, number and date when the method is CHEQUE; a relationship in which the caller sells to the
 * buyer, ACTIVE or SUSPENDED ({@link RelationshipQueries#settlementRelationship}; wave 2,
 * M4MONEY-08); a cheque not recorded on an earlier receipt that did not bounce
 * ({@code m4.payment.cheque_recorded}; M4MONEY-09); each chosen settlement names an issued invoice
 * of this seller to this buyer, once, for more than zero and no more than its amount due ({@code m4.payment.exceeds_due}); the
 * settlements together no more than the receipt ({@code m4.payment.exceeds_receipt}).
 *
 * <p>With no settlements chosen, the receipt settles the buyer's open invoices oldest first (by tax
 * point date), passing over an invoice the buyer disputes; whatever is left stays on the buyer's
 * account as unapplied (24A: {@code unapplied = amount - sum}), which lowers its exposure. So an
 * overpayment is never refused: it is held on account.
 *
 * <p>Mutation: the seller's ENTITY series of PRC, the issuance (one line carrying the amount),
 * {@code doc_payment_receipt}, the cheque, one {@code payment_allocation} row per invoice settled
 * and each invoice's {@code settled_amount} cache recomputed from its rows. Audit
 * PAYMENT_RECEIPT_RECORDED; events payment_receipt.recorded.v1 and journal.postings_ready.v1 (PRC
 * RECEIPT, the seller's side).
 */
@Service
@CommandHandler(permission = "bil.payment.record")
public class RecordPaymentReceiptHandler implements Handles<RecordPaymentReceipt, UUID> {

    public static final String PRC = "PRC";
    static final String AUDIT_RECORDED = "PAYMENT_RECEIPT_RECORDED";
    static final Set<String> METHODS = Set.of("CASH", "CHEQUE", "TRANSFER", "DEPOSIT");

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final DocumentIssuance issuance;
    private final TradingSeries series;
    private final RelationshipQueries relationships;
    private final InvoiceSettlements settlements;
    private final SettlementPlanner planner;
    private final PostingMapper postings;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    @SuppressWarnings("java:S107") // the collaborators of one handler specification
    RecordPaymentReceiptHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            DocumentIssuance issuance,
            TradingSeries series,
            RelationshipQueries relationships,
            InvoiceSettlements settlements,
            SettlementPlanner planner,
            PostingMapper postings,
            TradingClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.issuance = issuance;
        this.series = series;
        this.relationships = relationships;
        this.settlements = settlements;
        this.planner = planner;
        this.postings = postings;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RecordPaymentReceipt command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID seller = scope.entityId();
        UUID buyer = TradingGuards.required(command.buyerEntityId(), "buyerEntityId");
        String method = TradingGuards.required(command.method(), "method");
        if (!METHODS.contains(method)) {
            throw new ProblemException("m4.payment.method_invalid", Map.of("method", method));
        }
        BigDecimal amount = command.amount();
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
            throw new ProblemException("m4.payment.amount_invalid");
        }
        LocalDate today = clock.today();
        LocalDate receivedOn = command.receivedOn() == null ? today : command.receivedOn();
        if (receivedOn.isAfter(today)) {
            throw new ProblemException("m4.payment.received_in_future");
        }
        RecordPaymentReceipt.Cheque cheque = command.cheque();
        if (RecordPaymentReceipt.CHEQUE.equals(method)
                && (cheque == null
                        || cheque.bank() == null
                        || cheque.bank().isBlank()
                        || cheque.chequeNo() == null
                        || cheque.chequeNo().isBlank()
                        || cheque.dated() == null)) {
            throw new ProblemException("m4.payment.cheque_required");
        }
        // Any relationship the pair traded under, ACTIVE or SUSPENDED, in force on the day received
        // or else the latest (CR-21A-7 section 6.1): money from a suspended buyer, the one that
        // owes, is recorded; only new trading asks for an ACTIVE row (wave 2, M4MONEY-08).
        RelationshipView relationship = relationships
                .settlementRelationship(seller, buyer, receivedOn, scope)
                .orElseThrow(() -> new ProblemException("m4.payment.no_relationship"));
        if (RecordPaymentReceipt.CHEQUE.equals(method)) {
            requireChequeNotRecorded(seller, buyer, cheque);
        }

        List<RecordPaymentReceipt.Settlement> applied = command.settlements().isEmpty()
                ? planner.oldestFirst(seller, buyer, amount)
                : planner.chosen(command.settlements(), seller, buyer, amount, "m4.payment.exceeds_receipt");
        BigDecimal sum =
                applied.stream().map(RecordPaymentReceipt.Settlement::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unapplied = amount.subtract(sum);

        UUID receiptId = Ids.next();
        String reference = command.reference() == null || command.reference().isBlank()
                ? null
                : command.reference().strip();
        documents.save(TradingDocuments.draft(receiptId, PRC, seller, buyer, null, scope.userId(), null, reference));
        documents.saveLines(receiptId, List.of(amountLine(receiptId, amount)));
        series.ensureEntitySeries(PRC, scope);
        DocumentRecord issued = issuance.issue(
                documents.findByIdForUpdate(receiptId).orElseThrow(), documents.findLines(receiptId), scope);
        jdbc.update(
                """
                insert into trading.doc_payment_receipt (document_id, relationship_id, seller_entity_id,
                    payer_entity_id, method, reference, received_on, amount)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                receiptId,
                relationship.relationshipId(),
                seller,
                buyer,
                method,
                reference,
                receivedOn,
                amount);
        if (RecordPaymentReceipt.CHEQUE.equals(method)) {
            jdbc.update(
                    "insert into trading.cheque (receipt_document_id, bank, cheque_no, dated) values (?, ?, ?, ?)",
                    receiptId,
                    cheque.bank().strip(),
                    cheque.chequeNo().strip(),
                    cheque.dated());
        }
        for (RecordPaymentReceipt.Settlement settlement : applied) {
            jdbc.update(
                    """
                    insert into trading.payment_allocation (allocation_id, receipt_document_id, invoice_document_id,
                        amount)
                    values (?, ?, ?, ?)
                    """,
                    Ids.next(),
                    receiptId,
                    settlement.invoiceId(),
                    settlement.amount());
            jdbc.update(
                    "update trading.doc_invoice set settled_amount = ? where document_id = ?",
                    settlements.settled(settlement.invoiceId()),
                    settlement.invoiceId());
        }

        List<Posting> journal = postings.postings(PRC, "RECEIPT", "SELLER", Map.of("applied", amount));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("docNumber", issued.docNumberDisplay());
        after.put("buyerEntityId", buyer);
        after.put("method", method);
        after.put("amount", amount);
        after.put("receivedOn", receivedOn);
        after.put("settled", sum);
        after.put("unapplied", unapplied);
        after.put("invoices", applied.size());
        audit.record(AUDIT_RECORDED, Subject.of("payment_receipt", receiptId), null, after, scope);
        events.publish(new PaymentReceiptRecorded(
                receiptId,
                issued.docNumberDisplay(),
                relationship.relationshipId(),
                seller,
                buyer,
                method,
                amount,
                receivedOn,
                applied,
                unapplied));
        events.publish(new JournalPostingsReady(
                receiptId, PRC, issued.docNumberDisplay(), seller, journal, issued.businessDate()));
        return receiptId;
    }

    /**
     * Each cheque once (wave 2, CR-24A-3 item 3, M4MONEY-09). A cheque is the seller, the payer,
     * the bank ({@code upper(btrim(bank))}) and the number ({@code ltrim(btrim(cheque_no), '0')}),
     * not its date: the same number from the same drawer with another date is a mistake or worse,
     * and a replacement cheque has a new number. The payer is in the key because two drawers at
     * one bank can hold one number. It is recorded again only when every earlier receipt of this
     * seller from this payer carrying it bounced (a re-presented cheque); otherwise
     * {@code m4.payment.cheque_recorded}, naming the earlier receipt. No unique index can say
     * this (the outcome lives in another table, and a bounced cheque comes back with the same
     * number), so the look-up runs under an advisory lock on the key, and two clerks recording one
     * cheque at once are serialised: the second sees the first's receipt.
     */
    private void requireChequeNotRecorded(UUID seller, UUID payer, RecordPaymentReceipt.Cheque cheque) {
        // Normalised in SQL, the same expression on both sides; the values are stripped as they are stored.
        String bank = cheque.bank().strip();
        String number = cheque.chequeNo().strip();
        jdbc.queryForList(
                """
                select pg_advisory_xact_lock(hashtext('cheque-' || ?::text || '|' || ?::text || '|'
                       || upper(btrim(?::text)) || '|' || ltrim(btrim(?::text), '0')))
                """,
                seller,
                payer,
                bank,
                number);
        List<UUID> earlier = jdbc.queryForList(
                """
                select c.receipt_document_id from trading.cheque c
                  join trading.doc_payment_receipt r on r.document_id = c.receipt_document_id
                 where r.seller_entity_id = ? and r.payer_entity_id = ?
                   and upper(btrim(c.bank)) = upper(btrim(?::text))
                   and ltrim(btrim(c.cheque_no), '0') = ltrim(btrim(?::text), '0')
                   and not exists (select 1 from trading.cheque_outcome o
                                    where o.receipt_document_id = c.receipt_document_id and o.outcome = 'BOUNCED')
                 order by c.receipt_document_id
                 limit 1
                """,
                UUID.class,
                seller,
                payer,
                bank,
                number);
        if (!earlier.isEmpty()) {
            UUID receiptId = earlier.get(0);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("receiptId", receiptId);
            documents.findById(receiptId).ifPresent(receipt -> detail.put("docNumber", receipt.docNumberDisplay()));
            throw new ProblemException("m4.payment.cheque_recorded", detail);
        }
    }

    /** The one line of a receipt: no item, quantity one, the amount as its total (a reversal's negated). */
    static DocumentLineRecord amountLine(UUID receiptId, BigDecimal lineTotal) {
        return TradingDocuments.line(
                Ids.next(),
                receiptId,
                1,
                null,
                null,
                null,
                BigDecimal.ONE,
                lineTotal.abs(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                lineTotal,
                null,
                null);
    }
}
