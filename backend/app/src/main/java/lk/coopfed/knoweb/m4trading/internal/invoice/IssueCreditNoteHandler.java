package lk.coopfed.knoweb.m4trading.internal.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import lk.coopfed.knoweb.m4trading.api.CreditNoteIssued;
import lk.coopfed.knoweb.m4trading.api.IssueCreditNote;
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
 * IssueCreditNote (24A section 6), chosen lines. Guards, in order: the seller's entity-wide OWN
 * scope; an invoice and a reason; the caller's own issued invoice; at least one line; each a line
 * of this invoice with a positive quantity no more than the line's.
 *
 * <p>Mutation: the seller's ENTITY series of CN, the issuance, the CREDITS link to the invoice with
 * the gross amount (the kernel refuses more than the open balance, {@code
 * document.link.exceeds_balance}), {@code doc_credit_note}, and the invoice's {@code
 * credited_amount} cache recomputed from its CREDITS links. Audit CREDIT_NOTE_ISSUED; events
 * credit_note.issued.v1 and journal.postings_ready.v1 (the seller's side, by {@link PostingMapper}).
 * The permission {@code bil.creditnote.issue} asks for a fresh second factor (doc 19 section 2.2).
 *
 * <p>A discrepancy is settled by {@link SettleDiscrepancyHandler}, not here.
 */
@Service
@CommandHandler(permission = "bil.creditnote.issue")
public class IssueCreditNoteHandler implements Handles<IssueCreditNote, UUID> {

    public static final String CN = "CN";
    public static final String AUDIT_ISSUED = "CREDIT_NOTE_ISSUED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final DocumentIssuance issuance;
    private final DocumentLinks links;
    private final TradingSeries series;
    private final PostingMapper postings;
    private final InvoiceSettlements settlements;
    private final AuditFacade audit;
    private final EventPublisher events;

    @SuppressWarnings("java:S107") // the collaborators of one handler specification
    IssueCreditNoteHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            DocumentIssuance issuance,
            DocumentLinks links,
            TradingSeries series,
            PostingMapper postings,
            InvoiceSettlements settlements,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.issuance = issuance;
        this.links = links;
        this.series = series;
        this.postings = postings;
        this.settlements = settlements;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(IssueCreditNote command, ScopeContext scope) {
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
            throw new ProblemException("m4.creditnote.not_seller");
        }
        if (!invoice.isIssued()) {
            throw new ProblemException("m4.invoice.not_issued");
        }
        if (command.lines().isEmpty()) {
            throw new ProblemException("m4.creditnote.nothing_to_credit");
        }
        UUID creditNoteId = Ids.next();
        List<DocumentLineRecord> lines = chosenLines(creditNoteId, command.lines(), documents.findLines(invoiceId));

        documents.save(TradingDocuments.draft(
                creditNoteId, CN, seller, invoice.counterpartyEntityId(), null, scope.userId(), invoiceId, reason));
        documents.saveLines(creditNoteId, lines);
        series.ensureEntitySeries(CN, scope);
        DocumentRecord issued = issuance.issue(
                documents.findByIdForUpdate(creditNoteId).orElseThrow(), documents.findLines(creditNoteId), scope);
        // Paid in part: no more than is still due is credited (m4.creditnote.exceeds_due).
        settlements.requireDue(invoiceId, issued.grossAmount());
        links.link(creditNoteId, invoiceId, LinkType.CREDITS, issued.grossAmount(), scope);
        jdbc.update(
                "insert into trading.doc_credit_note (document_id, invoice_document_id, reason) values (?, ?, ?)",
                creditNoteId,
                invoiceId,
                reason);
        BigDecimal credited = creditedFromLinks(documents, invoiceId);
        jdbc.update("update trading.doc_invoice set credited_amount = ? where document_id = ?", credited, invoiceId);

        List<Posting> journal =
                postings.postings(CN, "GOODS", "SELLER", Map.of("net", issued.netAmount(), "tax", issued.taxAmount()));

        audit.record(
                AUDIT_ISSUED,
                Subject.of("credit_note", creditNoteId),
                null,
                auditAfter(issued, invoiceId, null, credited),
                scope);
        events.publish(issuedEvent(issued, invoiceId, null, seller, invoice.counterpartyEntityId()));
        events.publish(new JournalPostingsReady(creditNoteId, CN, issued.docNumberDisplay(), seller, journal));
        return creditNoteId;
    }

    /** Chosen quantities of the invoice's own lines, at their prices and rates. */
    private static List<DocumentLineRecord> chosenLines(
            UUID creditNoteId, List<IssueCreditNote.Line> chosen, List<DocumentLineRecord> invoiceLines) {
        List<DocumentLineRecord> lines = new ArrayList<>();
        int lineNo = 0;
        for (IssueCreditNote.Line want : chosen) {
            if (want == null || want.invoiceLineId() == null) {
                throw new ProblemException("m4.creditnote.line_unknown");
            }
            DocumentLineRecord billed = invoiceLines.stream()
                    .filter(line -> want.invoiceLineId().equals(line.id()))
                    .findFirst()
                    .orElseThrow(() -> new ProblemException("m4.creditnote.line_unknown"));
            if (want.qty() == null || want.qty().signum() <= 0 || want.qty().compareTo(billed.qty()) > 0) {
                throw new ProblemException("m4.creditnote.qty_invalid", Map.of("skuId", billed.skuId()));
            }
            lineNo++;
            lines.add(priced(creditNoteId, lineNo, billed, want.qty()));
        }
        return lines;
    }

    /**
     * A credit note line for a quantity of an invoice line, at its price and VAT rate: net to the
     * cent, VAT per line to the cent, as the invoice builds its own.
     */
    public static DocumentLineRecord priced(UUID creditNoteId, int lineNo, DocumentLineRecord billed, BigDecimal qty) {
        BigDecimal net = billed.unitPrice().multiply(qty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tax = net.multiply(billed.taxRatePercent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return TradingDocuments.line(
                Ids.next(),
                creditNoteId,
                lineNo,
                billed.skuId(),
                billed.batchId(),
                billed.uomCode(),
                qty,
                billed.unitPrice(),
                billed.taxRatePercent(),
                tax,
                net,
                null,
                billed.id());
    }

    /** The invoice's credited amount, recomputed from its CREDITS links, never added to (doc 24 9.4). */
    public static BigDecimal creditedFromLinks(DocumentBaseRepository documents, UUID invoiceId) {
        return documents.findLinks(invoiceId).stream()
                .filter(link -> link.linkType() == LinkType.CREDITS && invoiceId.equals(link.toDocumentId()))
                .map(link -> link.amount() == null ? BigDecimal.ZERO : link.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static Map<String, Object> auditAfter(
            DocumentRecord issued, UUID invoiceId, UUID discrepancyId, BigDecimal credited) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", issued.status());
        after.put("docNumber", issued.docNumberDisplay());
        after.put("invoiceId", invoiceId);
        if (discrepancyId != null) {
            after.put("discrepancyId", discrepancyId);
        }
        after.put("netAmount", issued.netAmount());
        after.put("taxAmount", issued.taxAmount());
        after.put("grossAmount", issued.grossAmount());
        after.put("invoiceCreditedAmount", credited);
        return after;
    }

    public static CreditNoteIssued issuedEvent(
            DocumentRecord issued, UUID invoiceId, UUID discrepancyId, UUID seller, UUID buyer) {
        return new CreditNoteIssued(
                issued.id(),
                issued.docNumberDisplay(),
                invoiceId,
                discrepancyId,
                seller,
                buyer,
                issued.netAmount(),
                issued.taxAmount(),
                issued.grossAmount(),
                issued.contentHash());
    }
}
