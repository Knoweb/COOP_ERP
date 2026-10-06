package lk.coopfed.knoweb.m4trading.internal.invoice;

import java.math.BigDecimal;
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
 * scope; an invoice and a reason; the caller's own issued invoice; at least one line; then, under
 * the invoice's lock, each a line of this invoice ({@code m4.creditnote.line_unknown}) named once
 * ({@code m4.creditnote.line_duplicate}) with a quantity above zero of at most three decimals
 * ({@code m4.creditnote.qty_invalid}) no more than the line has left uncredited by earlier credit
 * notes, whichever path issued them ({@code m4.creditnote.exceeds_billed}; {@link InvoiceCredits}).
 *
 * <p>Mutation: the seller's ENTITY series of CN, the issuance, the CREDITS link to the invoice for
 * as much of the gross as the invoice still owes ({@link InvoiceSettlements#applyUpToDue}; none
 * when nothing is due, and the rest is the credit note's unapplied amount, applied later by
 * {@link ApplyCreditNoteHandler}), {@code doc_credit_note}, and the invoice's {@code
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
    private final InvoiceCredits credits;
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
            InvoiceCredits credits,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.issuance = issuance;
        this.links = links;
        this.series = series;
        this.postings = postings;
        this.settlements = settlements;
        this.credits = credits;
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
        // Locked before the lines and the credits are read: two credit notes of one invoice in
        // flight at once see each other (the lock is re-entrant, so applyUpToDue takes it again).
        documents.lockForLinking(invoiceId);
        UUID creditNoteId = Ids.next();
        List<DocumentLineRecord> lines = chosenLines(
                creditNoteId, command.lines(), documents.findLines(invoiceId), credits.creditedByLine(invoiceId));

        documents.save(TradingDocuments.draft(
                creditNoteId, CN, seller, invoice.counterpartyEntityId(), null, scope.userId(), invoiceId, reason));
        documents.saveLines(creditNoteId, lines);
        series.ensureEntitySeries(CN, scope);
        DocumentRecord issued = issuance.issue(
                documents.findByIdForUpdate(creditNoteId).orElseThrow(), documents.findLines(creditNoteId), scope);
        // Paid in part or in full: the money applies as far as the invoice is still due, the rest
        // stays on the credit note, unapplied (B-1).
        BigDecimal applied = settlements.applyUpToDue(invoiceId, issued.grossAmount());
        if (applied.signum() > 0) {
            links.link(creditNoteId, invoiceId, LinkType.CREDITS, applied, scope);
        }
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
                auditAfter(issued, invoiceId, null, credited, applied),
                scope);
        events.publish(issuedEvent(issued, invoiceId, null, seller, invoice.counterpartyEntityId()));
        events.publish(new JournalPostingsReady(creditNoteId, CN, issued.docNumberDisplay(), seller, journal));
        return creditNoteId;
    }

    /**
     * Chosen quantities of the invoice's own lines, at their prices and rates: each line once
     * ({@code m4.creditnote.line_duplicate}), a quantity above zero with at most three decimals
     * ({@code m4.creditnote.qty_invalid}: {@code document_line.qty} keeps three, so 0.0004 would
     * credit money for a stored 0.000), and no more than the line has left uncredited ({@code
     * m4.creditnote.exceeds_billed}).
     */
    private static List<DocumentLineRecord> chosenLines(
            UUID creditNoteId,
            List<IssueCreditNote.Line> chosen,
            List<DocumentLineRecord> invoiceLines,
            Map<UUID, InvoiceCredits.Credited> credited) {
        List<DocumentLineRecord> lines = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        int lineNo = 0;
        for (IssueCreditNote.Line want : chosen) {
            if (want == null || want.invoiceLineId() == null) {
                throw new ProblemException("m4.creditnote.line_unknown");
            }
            DocumentLineRecord billed = invoiceLines.stream()
                    .filter(line -> want.invoiceLineId().equals(line.id()))
                    .findFirst()
                    .orElseThrow(() -> new ProblemException("m4.creditnote.line_unknown"));
            if (!seen.add(billed.id())) {
                throw new ProblemException("m4.creditnote.line_duplicate", Map.of("skuId", billed.skuId()));
            }
            if (want.qty() == null
                    || want.qty().signum() <= 0
                    || want.qty().stripTrailingZeros().scale() > 3) {
                throw new ProblemException("m4.creditnote.qty_invalid", Map.of("skuId", billed.skuId()));
            }
            InvoiceCredits.Credited left = InvoiceCredits.remaining(billed, credited.get(billed.id()));
            requireWithinBilled(billed, credited.get(billed.id()), left, want.qty());
            lineNo++;
            lines.add(InvoiceCredits.priced(creditNoteId, lineNo, billed, left, want.qty()));
        }
        return lines;
    }

    /**
     * The quantity is no more than the line has left uncredited, else {@code
     * m4.creditnote.exceeds_billed} with the billed, credited and remaining quantities. The one
     * rule of the three credit paths (ApproveClaim refuses with it too).
     */
    public static void requireWithinBilled(
            DocumentLineRecord billed, InvoiceCredits.Credited credited, InvoiceCredits.Credited left, BigDecimal qty) {
        if (qty.compareTo(left.qty()) > 0) {
            BigDecimal done = credited == null ? BigDecimal.ZERO : credited.qty();
            throw new ProblemException(
                    "m4.creditnote.exceeds_billed",
                    Map.of(
                            "skuId", billed.skuId(),
                            "billed", billed.qty().toPlainString(),
                            "credited", done.toPlainString(),
                            "remaining", left.qty().max(BigDecimal.ZERO).toPlainString()));
        }
    }

    /** The invoice's credited amount, recomputed from its CREDITS links, never added to (doc 24 9.4). */
    public static BigDecimal creditedFromLinks(DocumentBaseRepository documents, UUID invoiceId) {
        return documents.findLinks(invoiceId).stream()
                .filter(link -> link.linkType() == LinkType.CREDITS && invoiceId.equals(link.toDocumentId()))
                .map(link -> link.amount() == null ? BigDecimal.ZERO : link.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * @param applied what of the credit note's gross was applied to the invoice (B-1); the rest is
     *     its unapplied amount
     */
    public static Map<String, Object> auditAfter(
            DocumentRecord issued, UUID invoiceId, UUID discrepancyId, BigDecimal credited, BigDecimal applied) {
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
        after.put("appliedAmount", applied);
        after.put("unappliedAmount", issued.grossAmount().subtract(applied));
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
