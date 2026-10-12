package lk.coopfed.knoweb.m4trading.internal.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
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
import lk.coopfed.knoweb.kernel.api.DocumentLinks;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.LinkType;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m4trading.api.DebitNoteIssued;
import lk.coopfed.knoweb.m4trading.api.IssueDebitNote;
import lk.coopfed.knoweb.m4trading.api.JournalPostingsReady;
import lk.coopfed.knoweb.m4trading.api.Posting;
import lk.coopfed.knoweb.m4trading.internal.document.TradingDocuments;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import lk.coopfed.knoweb.m4trading.internal.document.TradingSeries;
import lk.coopfed.knoweb.m4trading.internal.posting.PostingMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IssueDebitNote (24A section 6), chosen lines. Guards, in order: the seller's entity-wide OWN
 * scope; an invoice and a reason; the caller's own issued invoice, not disputed; at least one line;
 * each a line of this invoice, named once, with a quantity above zero of at most three decimals.
 *
 * <p>Mutation: the seller's ENTITY series of DN2, the issuance, the DEBITS link to the invoice with
 * the gross amount, {@code doc_debit_note}, and the invoice's {@code
 * debited_amount} cache recomputed from its DEBITS links. Audit DEBIT_NOTE_ISSUED; events
 * debit_note.issued.v1 and journal.postings_ready.v1 (the seller's side, by {@link PostingMapper}).
 */
@Service
@CommandHandler(permission = "bil.debitnote.issue")
public class IssueDebitNoteHandler implements Handles<IssueDebitNote, UUID> {

    public static final String DN2 = "DN2";
    public static final String AUDIT_ISSUED = "DEBIT_NOTE_ISSUED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final DocumentIssuance issuance;
    private final DocumentLinks links;
    private final TradingSeries series;
    private final PostingMapper postings;
    private final InvoiceDisputes disputes;
    private final AuditFacade audit;
    private final EventPublisher events;

    @SuppressWarnings("java:S107")
    IssueDebitNoteHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            DocumentIssuance issuance,
            DocumentLinks links,
            TradingSeries series,
            PostingMapper postings,
            InvoiceDisputes disputes,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.issuance = issuance;
        this.links = links;
        this.series = series;
        this.postings = postings;
        this.disputes = disputes;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(IssueDebitNote command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID seller = scope.entityId();
        UUID invoiceId = TradingGuards.required(command.invoiceId(), "invoiceId");
        String reason = TradingGuards.required(command.reason(), "reason").strip();
        DocumentRecord invoice = documents
                .findById(invoiceId)
                .filter(document -> IssueInvoiceHandler.INV.equals(document.docTypeCode()))
                .orElseThrow(() -> new ProblemException("m4.invoice.not_found"));
        if (!seller.equals(invoice.ownerEntityId())) {
            throw new ProblemException("m4.debitnote.not_seller");
        }
        if (!invoice.isIssued()) {
            throw new ProblemException("m4.invoice.not_issued");
        }
        if (disputes.isDisputed(invoiceId)) {
            throw new ProblemException("m4.debitnote.invoice_disputed", Map.of("invoiceId", invoiceId));
        }
        if (command.lines().isEmpty()) {
            throw new ProblemException("m4.debitnote.nothing_to_debit");
        }
        documents.lockForLinking(invoiceId);
        UUID debitNoteId = Ids.next();
        List<DocumentLineRecord> lines = chosenLines(debitNoteId, command.lines(), documents.findLines(invoiceId));

        documents.save(TradingDocuments.draft(
                debitNoteId, DN2, seller, invoice.counterpartyEntityId(), null, scope.userId(), invoiceId, reason));
        documents.saveLines(debitNoteId, lines);
        series.ensureEntitySeries(DN2, scope);
        DocumentRecord issued = issuance.issue(
                documents.findByIdForUpdate(debitNoteId).orElseThrow(), documents.findLines(debitNoteId), scope);

        links.link(debitNoteId, invoiceId, LinkType.DEBITS, issued.grossAmount(), scope);
        jdbc.update(
                "insert into trading.doc_debit_note (document_id, invoice_document_id, reason) values (?, ?, ?)",
                debitNoteId,
                invoiceId,
                reason);
        BigDecimal debited = debitedFromLinks(documents, invoiceId);
        jdbc.update("update trading.doc_invoice set debited_amount = ? where document_id = ?", debited, invoiceId);

        List<Posting> journal = postings.postings(
                DN2, "CHARGE", "SELLER", Map.of("net", issued.netAmount(), "tax", issued.taxAmount()));

        audit.record(
                AUDIT_ISSUED,
                Subject.of("debit_note", debitNoteId),
                null,
                auditAfter(issued, invoiceId, debited),
                scope);
        events.publish(issuedEvent(issued, invoiceId, seller, invoice.counterpartyEntityId()));
        events.publish(new JournalPostingsReady(
                debitNoteId,
                DN2,
                issued.docNumberDisplay(),
                seller,
                journal,
                issued.businessDate(),
                invoice.counterpartyEntityId()));
        return debitNoteId;
    }

    /**
     * Chosen quantities of the invoice's own lines, at their prices and rates: each line once
     * ({@code m4.debitnote.line_duplicate}), a quantity above zero with at most three decimals
     * ({@code m4.debitnote.qty_invalid}: {@code document_line.qty} keeps three, so 0.0004 would
     * debit money for a stored 0.000). The credit note's guards of wave 2 (M4MONEY-02, -04),
     * carried over (wave 3, M4-07).
     */
    private static List<DocumentLineRecord> chosenLines(
            UUID debitNoteId, List<IssueDebitNote.Line> chosen, List<DocumentLineRecord> invoiceLines) {
        List<DocumentLineRecord> lines = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        int lineNo = 0;
        for (IssueDebitNote.Line want : chosen) {
            if (want == null || want.invoiceLineId() == null) {
                throw new ProblemException("m4.debitnote.line_unknown");
            }
            DocumentLineRecord billed = invoiceLines.stream()
                    .filter(line -> want.invoiceLineId().equals(line.id()))
                    .findFirst()
                    .orElseThrow(() -> new ProblemException("m4.debitnote.line_unknown"));
            if (!seen.add(billed.id())) {
                throw new ProblemException("m4.debitnote.line_duplicate", Map.of("skuId", billed.skuId()));
            }
            if (want.qty() == null
                    || want.qty().signum() <= 0
                    || want.qty().stripTrailingZeros().scale() > 3) {
                throw new ProblemException("m4.debitnote.qty_invalid", Map.of("skuId", billed.skuId()));
            }
            lineNo++;
            BigDecimal net = billed.unitPrice().multiply(want.qty()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal tax =
                    net.multiply(billed.taxRatePercent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            lines.add(TradingDocuments.line(
                    Ids.next(),
                    debitNoteId,
                    lineNo,
                    billed.skuId(),
                    billed.batchId(),
                    billed.uomCode(),
                    want.qty(),
                    billed.unitPrice(),
                    billed.taxRatePercent(),
                    tax,
                    net,
                    null,
                    billed.id()));
        }
        return lines;
    }

    /** The invoice's debited amount, recomputed from its DEBITS links. */
    public static BigDecimal debitedFromLinks(DocumentBaseRepository documents, UUID invoiceId) {
        return documents.findLinks(invoiceId).stream()
                .filter(link -> link.linkType() == LinkType.DEBITS && invoiceId.equals(link.toDocumentId()))
                .map(link -> link.amount() == null ? BigDecimal.ZERO : link.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static Map<String, Object> auditAfter(DocumentRecord issued, UUID invoiceId, BigDecimal debited) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", issued.status());
        after.put("docNumber", issued.docNumberDisplay());
        after.put("invoiceId", invoiceId);
        after.put("netAmount", issued.netAmount());
        after.put("taxAmount", issued.taxAmount());
        after.put("grossAmount", issued.grossAmount());
        after.put("invoiceDebitedAmount", debited);
        return after;
    }

    public static DebitNoteIssued issuedEvent(DocumentRecord issued, UUID invoiceId, UUID seller, UUID buyer) {
        return new DebitNoteIssued(
                issued.id(),
                issued.docNumberDisplay(),
                invoiceId,
                seller,
                buyer,
                issued.netAmount(),
                issued.taxAmount(),
                issued.grossAmount(),
                issued.contentHash());
    }
}
