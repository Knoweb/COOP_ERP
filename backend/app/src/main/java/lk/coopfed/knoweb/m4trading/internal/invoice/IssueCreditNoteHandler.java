package lk.coopfed.knoweb.m4trading.internal.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.TaxRateView;
import lk.coopfed.knoweb.m4trading.api.CreditNoteIssued;
import lk.coopfed.knoweb.m4trading.api.IssueCreditNote;
import lk.coopfed.knoweb.m4trading.api.JournalPostingsReady;
import lk.coopfed.knoweb.m4trading.api.Posting;
import lk.coopfed.knoweb.m4trading.internal.document.TradingDocuments;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import lk.coopfed.knoweb.m4trading.internal.document.TradingSeries;
import lk.coopfed.knoweb.m4trading.internal.grn.GrnReads;
import lk.coopfed.knoweb.m4trading.internal.posting.PostingMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IssueCreditNote (24A section 6). Guards, in order: the seller's entity-wide OWN scope; an invoice
 * and a reason; the caller's own issued invoice; a discrepancy or lines, not both. For a
 * discrepancy: the buyer's discrepancy raised against the caller, on a GRN this invoice billed, not
 * settled before (an advisory lock per discrepancy serialises two settlements). For lines: each a
 * line of this invoice, a positive quantity no more than the line's. Then something to credit.
 *
 * <p>The lines of a settlement: per discrepancy line, the short quantity (expected less received,
 * when received is less) plus the damaged quantity, at the price and VAT rate of the invoice line
 * of that GRN line; a GRN line the invoice did not bill (nothing received) at the GRN line's trade
 * price and the rate in force on the invoice's tax point. An over-delivered line credits nothing.
 *
 * <p>Mutation: the seller's ENTITY series of CN, the issuance (totals and the content hash frozen),
 * the CREDITS link to the invoice with the gross amount (the kernel refuses more than the invoice's
 * open balance, {@code document.link.exceeds_balance}), {@code doc_credit_note}, and the invoice's
 * {@code credited_amount} cache recomputed from its CREDITS links. Audit CREDIT_NOTE_ISSUED; events
 * credit_note.issued.v1 and journal.postings_ready.v1 (the seller's side, by {@link PostingMapper}).
 *
 * <p>The permission is {@code bil.creditnote.issue}, which asks for a fresh second factor (doc 19
 * section 2.2); the settlement of a discrepancy takes it as well (24A DR-1 waives it only for a
 * proposal the buyer accepted, and the two-step flow is deferred).
 */
@Service
@CommandHandler(permission = "bil.creditnote.issue")
public class IssueCreditNoteHandler implements Handles<IssueCreditNote, UUID> {

    static final String CN = "CN";
    static final String AUDIT_ISSUED = "CREDIT_NOTE_ISSUED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final DocumentIssuance issuance;
    private final DocumentLinks links;
    private final TradingSeries series;
    private final CatalogueQueries catalogue;
    private final PostingMapper postings;
    private final AuditFacade audit;
    private final EventPublisher events;

    IssueCreditNoteHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            DocumentIssuance issuance,
            DocumentLinks links,
            TradingSeries series,
            CatalogueQueries catalogue,
            PostingMapper postings,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.issuance = issuance;
        this.links = links;
        this.series = series;
        this.catalogue = catalogue;
        this.postings = postings;
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
        UUID discrepancyId = command.discrepancyId();
        if ((discrepancyId == null) == command.lines().isEmpty()) {
            throw new ProblemException("m4.creditnote.discrepancy_or_lines");
        }
        List<DocumentLineRecord> invoiceLines = documents.findLines(invoiceId);
        UUID creditNoteId = Ids.next();
        List<DocumentLineRecord> lines = discrepancyId != null
                ? settlementLines(creditNoteId, invoiceId, discrepancyId, invoiceLines, seller, scope)
                : chosenLines(creditNoteId, command.lines(), invoiceLines);
        if (lines.isEmpty()) {
            throw new ProblemException("m4.creditnote.nothing_to_credit");
        }

        documents.save(TradingDocuments.draft(
                creditNoteId, CN, seller, invoice.counterpartyEntityId(), null, scope.userId(), invoiceId, reason));
        documents.saveLines(creditNoteId, lines);
        series.ensureEntitySeries(CN, scope);
        DocumentRecord issued = issuance.issue(
                documents.findByIdForUpdate(creditNoteId).orElseThrow(), documents.findLines(creditNoteId), scope);
        links.link(creditNoteId, invoiceId, LinkType.CREDITS, issued.grossAmount(), scope);
        jdbc.update(
                """
                insert into trading.doc_credit_note (document_id, invoice_document_id, discrepancy_document_id, reason)
                values (?, ?, ?, ?)
                """,
                creditNoteId,
                invoiceId,
                discrepancyId,
                reason);
        // The cache is recomputed from the links, never added to (doc 24 section 9.4).
        BigDecimal credited = documents.findLinks(invoiceId).stream()
                .filter(link -> link.linkType() == LinkType.CREDITS && invoiceId.equals(link.toDocumentId()))
                .map(link -> link.amount() == null ? BigDecimal.ZERO : link.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        jdbc.update("update trading.doc_invoice set credited_amount = ? where document_id = ?", credited, invoiceId);

        List<Posting> journal =
                postings.postings(CN, "GOODS", "SELLER", Map.of("net", issued.netAmount(), "tax", issued.taxAmount()));

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
        audit.record(AUDIT_ISSUED, Subject.of("credit_note", creditNoteId), null, after, scope);

        events.publish(new CreditNoteIssued(
                creditNoteId,
                issued.docNumberDisplay(),
                invoiceId,
                discrepancyId,
                seller,
                invoice.counterpartyEntityId(),
                issued.netAmount(),
                issued.taxAmount(),
                issued.grossAmount(),
                issued.contentHash()));
        events.publish(new JournalPostingsReady(creditNoteId, CN, issued.docNumberDisplay(), seller, journal));
        return creditNoteId;
    }

    /** The short and damaged quantities of the buyer's discrepancy, at the invoice's prices. */
    private List<DocumentLineRecord> settlementLines(
            UUID creditNoteId,
            UUID invoiceId,
            UUID discrepancyId,
            List<DocumentLineRecord> invoiceLines,
            UUID seller,
            ScopeContext scope) {
        DocumentRecord discrepancy = documents
                .findById(discrepancyId)
                .filter(document -> GrnReads.DISC.equals(document.docTypeCode()))
                .filter(document -> seller.equals(document.counterpartyEntityId()))
                .orElseThrow(() -> new ProblemException("m4.discrepancy.not_found"));
        List<Map<String, Object>> invoiceRow = jdbc.queryForList(
                """
                select tax_point_date from trading.doc_invoice
                 where document_id = ?
                   and (select grn_document_id from trading.doc_discrepancy where document_id = ?)
                       = any (grn_document_ids)
                """,
                invoiceId,
                discrepancy.id());
        if (invoiceRow.isEmpty()) {
            throw new ProblemException("m4.creditnote.discrepancy_not_invoiced");
        }
        LocalDate taxPoint = ((java.sql.Date) invoiceRow.get(0).get("tax_point_date")).toLocalDate();
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?::text))", "credit-note-" + discrepancyId);
        Integer settled = jdbc.queryForObject(
                "select count(*) from trading.doc_credit_note where discrepancy_document_id = ?",
                Integer.class,
                discrepancyId);
        if (settled != null && settled > 0) {
            throw new ProblemException("m4.creditnote.discrepancy_settled");
        }

        // The discrepancy's kernel lines carry the item, batch, unit and trade price of each GRN
        // line (ConfirmGrnHandler.raise); its extension lines carry the counts.
        Map<UUID, DocumentLineRecord> reportedByGrnLine = new LinkedHashMap<>();
        for (DocumentLineRecord line : documents.findLines(discrepancyId)) {
            reportedByGrnLine.put(line.referenceLineId(), line);
        }
        List<DocumentLineRecord> lines = new ArrayList<>();
        int lineNo = 0;
        for (Map<String, Object> row : jdbc.queryForList(
                """
                select grn_line_id, variance_qty, damaged_qty from trading.doc_discrepancy_line
                 where document_id = ? order by grn_line_id
                """,
                discrepancyId)) {
            UUID grnLineId = (UUID) row.get("grn_line_id");
            BigDecimal variance = (BigDecimal) row.get("variance_qty");
            BigDecimal damaged = (BigDecimal) row.get("damaged_qty");
            BigDecimal qty = variance.signum() < 0 ? variance.negate() : BigDecimal.ZERO;
            if (damaged != null) {
                qty = qty.add(damaged);
            }
            DocumentLineRecord reported = reportedByGrnLine.get(grnLineId);
            if (qty.signum() <= 0 || reported == null) {
                continue;
            }
            Optional<DocumentLineRecord> billed = invoiceLines.stream()
                    .filter(line -> grnLineId.equals(line.referenceLineId()))
                    .findFirst();
            BigDecimal price = billed.map(DocumentLineRecord::unitPrice)
                    .orElse(reported.unitPrice() == null ? BigDecimal.ZERO : reported.unitPrice());
            BigDecimal rate = billed.map(DocumentLineRecord::taxRatePercent).orElseGet(() -> catalogue
                    .taxRateInForce(reported.skuId(), taxPoint, scope)
                    .map(TaxRateView::ratePercent)
                    .orElseThrow(() ->
                            new ProblemException("m4.invoice.tax_rate_missing", Map.of("skuId", reported.skuId()))));
            lineNo++;
            lines.add(priced(
                    creditNoteId,
                    lineNo,
                    reported.skuId(),
                    reported.batchId(),
                    reported.uomCode(),
                    qty,
                    price,
                    rate,
                    billed.map(DocumentLineRecord::id).orElse(null)));
        }
        return lines;
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
            lines.add(priced(
                    creditNoteId,
                    lineNo,
                    billed.skuId(),
                    billed.batchId(),
                    billed.uomCode(),
                    want.qty(),
                    billed.unitPrice(),
                    billed.taxRatePercent(),
                    billed.id()));
        }
        return lines;
    }

    /** A credit note line: net to the cent, VAT per line to the cent, as the invoice builds its own. */
    private static DocumentLineRecord priced(
            UUID creditNoteId,
            int lineNo,
            UUID skuId,
            UUID batchId,
            String uomCode,
            BigDecimal qty,
            BigDecimal price,
            BigDecimal rate,
            UUID invoiceLineId) {
        BigDecimal net = price.multiply(qty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tax = net.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return TradingDocuments.line(
                Ids.next(),
                creditNoteId,
                lineNo,
                skuId,
                batchId,
                uomCode,
                qty,
                price,
                rate,
                tax,
                net,
                null,
                invoiceLineId);
    }
}
