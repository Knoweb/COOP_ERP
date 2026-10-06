package lk.coopfed.knoweb.m4trading.internal.invoice;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
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
import lk.coopfed.knoweb.m4trading.api.DiscrepancySettled;
import lk.coopfed.knoweb.m4trading.api.JournalPostingsReady;
import lk.coopfed.knoweb.m4trading.api.Posting;
import lk.coopfed.knoweb.m4trading.api.SettleDiscrepancy;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingDocuments;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import lk.coopfed.knoweb.m4trading.internal.document.TradingSeries;
import lk.coopfed.knoweb.m4trading.internal.grn.GrnReads;
import lk.coopfed.knoweb.m4trading.internal.posting.PostingMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SettleDiscrepancy: the seller accepts the buyer's count (architect's decision of 28 Sep, on the
 * delegation). The invoice bills the RECEIVED quantity (DR-2: ownership passes at the GRN), so:
 *
 * <ul>
 *   <li>a short quantity was never billed and is settled with no money;
 *   <li>damaged quantity is inside the received quantity ({@code doc_grn_line} CHECK damaged_qty
 *       &lt;= received_qty) and so was billed: it is credited, at the invoice line's price and VAT
 *       rate, by a credit note issued in the same act, never more than the line has left
 *       uncredited by earlier credit notes ({@link InvoiceCredits}; wave 2, M4MONEY-01); when
 *       nothing is left the discrepancy settles with no money;
 *   <li>the credit note's money applies to the invoice as far as it is still due; the rest stays
 *       on the credit note, unapplied (B-1), so a paid invoice no longer blocks the settlement;
 *   <li>an over-delivered quantity was billed at the received quantity and is not credited here.
 * </ul>
 *
 * <p>Guards, in order: the seller's entity-wide OWN scope; a discrepancy and a reason; the buyer's
 * discrepancy raised with the caller; not settled before (an advisory lock per discrepancy); when
 * there is damaged quantity, the seller's issued invoice of the GRN. Mutation: the credit note when
 * one is due (as {@link IssueCreditNoteHandler}), then the seller's own settlement row in {@code
 * trading.discrepancy_settlement} (who, when, the credit note if any). The buyer's discrepancy is
 * never written (AGENTS.md idea 3). Audit DISCREPANCY_SETTLED (and CREDIT_NOTE_ISSUED); events
 * discrepancy.settled.v1 (and credit_note.issued.v1, journal.postings_ready.v1).
 */
@Service
@CommandHandler(permission = "bil.creditnote.issue")
public class SettleDiscrepancyHandler implements Handles<SettleDiscrepancy, UUID> {

    static final String AUDIT_SETTLED = "DISCREPANCY_SETTLED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final DocumentIssuance issuance;
    private final DocumentLinks links;
    private final TradingSeries series;
    private final PostingMapper postings;
    private final InvoiceSettlements settlements;
    private final InvoiceCredits credits;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    @SuppressWarnings("java:S107") // the collaborators of one handler specification
    SettleDiscrepancyHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            DocumentIssuance issuance,
            DocumentLinks links,
            TradingSeries series,
            PostingMapper postings,
            InvoiceSettlements settlements,
            InvoiceCredits credits,
            TradingClock clock,
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
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    /** @return the credit note issued, or null when nothing billed needed crediting */
    @Override
    @Transactional
    public UUID handle(SettleDiscrepancy command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID seller = scope.entityId();
        UUID discrepancyId = TradingGuards.required(command.discrepancyId(), "discrepancyId");
        String reason = TradingGuards.required(command.reason(), "reason").strip();
        DocumentRecord discrepancy = documents
                .findById(discrepancyId)
                .filter(document -> GrnReads.DISC.equals(document.docTypeCode()))
                .filter(document -> seller.equals(document.counterpartyEntityId()))
                .orElseThrow(() -> new ProblemException("m4.discrepancy.not_found"));
        UUID buyer = discrepancy.ownerEntityId();
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?::text))", "discrepancy-settle-" + discrepancyId);
        Integer settled = jdbc.queryForObject(
                "select count(*) from trading.discrepancy_settlement where discrepancy_document_id = ?",
                Integer.class,
                discrepancyId);
        if (settled != null && settled > 0) {
            throw new ProblemException("m4.discrepancy.settled_already");
        }
        UUID grnId = jdbc.queryForObject(
                "select grn_document_id from trading.doc_discrepancy where document_id = ?", UUID.class, discrepancyId);

        // The damaged quantity per GRN line: the only billed quantity the buyer did not take as good.
        Map<UUID, BigDecimal> damagedByGrnLine = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "select grn_line_id, damaged_qty from trading.doc_discrepancy_line where document_id = ?"
                        + " order by grn_line_id",
                discrepancyId)) {
            BigDecimal damaged = (BigDecimal) row.get("damaged_qty");
            if (damaged != null && damaged.signum() > 0) {
                damagedByGrnLine.put((UUID) row.get("grn_line_id"), damaged);
            }
        }

        UUID creditNoteId = null;
        DocumentRecord issued = null;
        UUID invoiceId = null;
        BigDecimal credited = null;
        BigDecimal applied = BigDecimal.ZERO;
        List<Posting> journal = List.of();
        if (!damagedByGrnLine.isEmpty()) {
            Optional<UUID> invoice = jdbc
                    .queryForList(
                            "select document_id from trading.doc_invoice where ? = any (grn_document_ids)"
                                    + " and seller_entity_id = ?",
                            UUID.class,
                            grnId,
                            seller)
                    .stream()
                    .findFirst();
            if (invoice.isEmpty()) {
                throw new ProblemException("m4.discrepancy.invoice_first");
            }
            invoiceId = invoice.get();
            // Locked before the lines and the credits are read (InvoiceCredits).
            documents.lockForLinking(invoiceId);
            List<DocumentLineRecord> invoiceLines = documents.findLines(invoiceId);
            Map<UUID, InvoiceCredits.Credited> creditedByLine = credits.creditedByLine(invoiceId);
            creditNoteId = Ids.next();
            List<DocumentLineRecord> lines = new ArrayList<>();
            int lineNo = 0;
            for (Map.Entry<UUID, BigDecimal> damaged : damagedByGrnLine.entrySet()) {
                Optional<DocumentLineRecord> billed = invoiceLines.stream()
                        .filter(line -> damaged.getKey().equals(line.referenceLineId()))
                        .findFirst();
                if (billed.isEmpty()) {
                    continue; // nothing of this GRN line was billed, so nothing to credit
                }
                // A claim may have credited some of the line already: credit the damaged quantity
                // only as far as the line has anything left uncredited.
                InvoiceCredits.Credited left = InvoiceCredits.remaining(
                        billed.get(), creditedByLine.get(billed.get().id()));
                BigDecimal qty = damaged.getValue().min(left.qty());
                if (qty.signum() <= 0) {
                    continue;
                }
                lineNo++;
                lines.add(InvoiceCredits.priced(creditNoteId, lineNo, billed.get(), left, qty));
            }
            if (lines.isEmpty()) {
                creditNoteId = null;
            } else {
                documents.save(TradingDocuments.draft(
                        creditNoteId,
                        IssueCreditNoteHandler.CN,
                        seller,
                        buyer,
                        null,
                        scope.userId(),
                        invoiceId,
                        reason));
                documents.saveLines(creditNoteId, lines);
                series.ensureEntitySeries(IssueCreditNoteHandler.CN, scope);
                issued = issuance.issue(
                        documents.findByIdForUpdate(creditNoteId).orElseThrow(),
                        documents.findLines(creditNoteId),
                        scope);
                applied = settlements.applyUpToDue(invoiceId, issued.grossAmount());
                if (applied.signum() > 0) {
                    links.link(creditNoteId, invoiceId, LinkType.CREDITS, applied, scope);
                }
                jdbc.update(
                        """
                        insert into trading.doc_credit_note (document_id, invoice_document_id, discrepancy_document_id,
                            reason)
                        values (?, ?, ?, ?)
                        """,
                        creditNoteId,
                        invoiceId,
                        discrepancyId,
                        reason);
                credited = IssueCreditNoteHandler.creditedFromLinks(documents, invoiceId);
                jdbc.update(
                        "update trading.doc_invoice set credited_amount = ? where document_id = ?",
                        credited,
                        invoiceId);
                journal = postings.postings(
                        IssueCreditNoteHandler.CN,
                        "GOODS",
                        "SELLER",
                        Map.of("net", issued.netAmount(), "tax", issued.taxAmount()));
            }
        }

        Instant settledAt = clock.now();
        jdbc.update(
                """
                insert into trading.discrepancy_settlement (discrepancy_document_id, credit_note_document_id, reason,
                    settled_by, settled_at, owner_entity_id, counterparty_entity_id)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                discrepancyId,
                creditNoteId,
                reason,
                scope.userId(),
                Timestamp.from(settledAt),
                seller,
                buyer);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "SETTLED");
        after.put("reason", reason);
        after.put("creditNoteId", creditNoteId);
        audit.record(AUDIT_SETTLED, Subject.of("discrepancy", discrepancyId), Map.of("status", "RAISED"), after, scope);
        if (issued != null) {
            audit.record(
                    IssueCreditNoteHandler.AUDIT_ISSUED,
                    Subject.of("credit_note", creditNoteId),
                    null,
                    IssueCreditNoteHandler.auditAfter(issued, invoiceId, discrepancyId, credited, applied),
                    scope);
        }

        events.publish(
                new DiscrepancySettled(discrepancyId, grnId, seller, buyer, creditNoteId, scope.userId(), settledAt));
        if (issued != null) {
            events.publish(IssueCreditNoteHandler.issuedEvent(issued, invoiceId, discrepancyId, seller, buyer));
            events.publish(new JournalPostingsReady(
                    creditNoteId, IssueCreditNoteHandler.CN, issued.docNumberDisplay(), seller, journal));
        }
        return creditNoteId;
    }
}
