package lk.coopfed.knoweb.m4trading.internal.claim;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Attachments;
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
import lk.coopfed.knoweb.m4trading.api.ApproveClaim;
import lk.coopfed.knoweb.m4trading.api.ClaimApproved;
import lk.coopfed.knoweb.m4trading.api.ClaimLine;
import lk.coopfed.knoweb.m4trading.api.JournalPostingsReady;
import lk.coopfed.knoweb.m4trading.api.Posting;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingDocuments;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import lk.coopfed.knoweb.m4trading.internal.document.TradingSeries;
import lk.coopfed.knoweb.m4trading.internal.invoice.InvoiceCredits;
import lk.coopfed.knoweb.m4trading.internal.invoice.InvoiceSettlements;
import lk.coopfed.knoweb.m4trading.internal.invoice.IssueCreditNoteHandler;
import lk.coopfed.knoweb.m4trading.internal.posting.PostingMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ApproveClaim (24A section 6; doc 24 section 4.5: "seller; attachments COMPLETE; credit note
 * issued in the same transaction (CREDITS link)"). The seller accepts the claim in whole or in
 * part; what it accepts is credited on its invoice of the GRN at the invoice line's price and VAT
 * rate, never more than the line has left uncredited by earlier credit notes, whichever path
 * issued them ({@link InvoiceCredits}; wave 2, M4MONEY-01).
 *
 * <p>Guards, in order: the seller's entity-wide OWN scope; a claim raised with the caller ({@code
 * m4.claim.not_found}); not decided (an advisory lock per claim; {@code m4.claim.decided}); every
 * photograph COMPLETE ({@code m4.claim.evidence_pending}); each accepted line a line of the claim
 * ({@code m4.claim.line_unknown}) once, 0 &lt;= qty &lt;= claimed ({@code m4.claim.qty_invalid}),
 * something accepted ({@code m4.claim.nothing_approved}: reject instead); the seller's issued
 * invoice of the GRN billing the lines ({@code m4.claim.invoice_first}); under the invoice's lock,
 * each accepted quantity no more than its invoice line has left uncredited ({@code
 * m4.creditnote.exceeds_billed}: refused, never capped, so the decision and the credit note agree).
 * The credit's money applies to the invoice as far as it is still due and the rest stays on the
 * credit note, unapplied (B-1).
 *
 * <p>Mutation: the credit note (CN from the seller's ENTITY series, CREDITS link when anything is due, {@code
 * doc_credit_note} naming the claim, the invoice's credited cache); the seller's own rows {@code
 * claim_decision} (APPROVED) and {@code claim_decision_line}. The buyer's claim is never written
 * (AGENTS.md idea 3). Audit CLAIM_APPROVED and CREDIT_NOTE_ISSUED; events claim.approved.v1,
 * credit_note.issued.v1, journal.postings_ready.v1.
 */
@Service
@CommandHandler(permission = "del.claim.decide")
public class ApproveClaimHandler implements Handles<ApproveClaim, UUID> {

    static final String AUDIT_APPROVED = "CLAIM_APPROVED";

    private final JdbcTemplate jdbc;
    private final ClaimReads claims;
    private final Attachments attachments;
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
    ApproveClaimHandler(
            JdbcTemplate jdbc,
            ClaimReads claims,
            Attachments attachments,
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
        this.claims = claims;
        this.attachments = attachments;
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

    /** @return the credit note issued */
    @Override
    @Transactional
    public UUID handle(ApproveClaim command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID seller = scope.entityId();
        ClaimReads.Claim claim = ClaimGuards.claimWith(claims, jdbc, command.claimId(), seller);
        ClaimGuards.requireEvidenceComplete(claim, attachments);
        UUID buyer = claim.header().ownerEntityId();

        Map<UUID, BigDecimal> approved = approvedQuantities(claim, command.lines());
        if (approved.values().stream().noneMatch(qty -> qty.signum() > 0)) {
            throw new ProblemException("m4.claim.nothing_approved");
        }
        Optional<UUID> invoice = jdbc
                .queryForList(
                        "select document_id from trading.doc_invoice where ? = any (grn_document_ids)"
                                + " and seller_entity_id = ?",
                        UUID.class,
                        claim.grnId(),
                        seller)
                .stream()
                .findFirst();
        if (invoice.isEmpty()) {
            throw new ProblemException("m4.claim.invoice_first");
        }
        UUID invoiceId = invoice.get();
        // Locked before the lines and the credits are read (InvoiceCredits).
        documents.lockForLinking(invoiceId);
        List<DocumentLineRecord> invoiceLines = documents.findLines(invoiceId);
        Map<UUID, InvoiceCredits.Credited> creditedByLine = credits.creditedByLine(invoiceId);
        UUID creditNoteId = Ids.next();
        List<DocumentLineRecord> creditLines = new ArrayList<>();
        List<ClaimLine> accepted = new ArrayList<>();
        for (ClaimLine line : claim.lines()) {
            BigDecimal qty = approved.get(line.claimLineId());
            if (qty.signum() == 0) {
                continue;
            }
            DocumentLineRecord billed = invoiceLines.stream()
                    .filter(candidate -> line.grnLineId().equals(candidate.referenceLineId()))
                    .findFirst()
                    .orElseThrow(() -> new ProblemException("m4.claim.invoice_first"));
            // Refused, not capped: the decision line and the credit note always agree, and the
            // seller approves less or rejects (wave 2, M4MONEY-01).
            InvoiceCredits.Credited credited = creditedByLine.get(billed.id());
            InvoiceCredits.Credited left = InvoiceCredits.remaining(billed, credited);
            IssueCreditNoteHandler.requireWithinBilled(billed, credited, left, qty);
            creditLines.add(InvoiceCredits.priced(creditNoteId, creditLines.size() + 1, billed, left, qty));
            accepted.add(new ClaimLine(
                    line.claimLineId(), line.grnLineId(), line.skuId(), line.batchId(), line.uomCode(), qty));
        }

        String reason = "Claim " + claim.header().docNumberDisplay()
                + (command.findings() == null || command.findings().isBlank()
                        ? ""
                        : ": " + command.findings().strip());
        documents.save(TradingDocuments.draft(
                creditNoteId, IssueCreditNoteHandler.CN, seller, buyer, null, scope.userId(), invoiceId, reason));
        documents.saveLines(creditNoteId, creditLines);
        series.ensureEntitySeries(IssueCreditNoteHandler.CN, scope);
        DocumentRecord issued = issuance.issue(
                documents.findByIdForUpdate(creditNoteId).orElseThrow(), documents.findLines(creditNoteId), scope);
        // The money applies as far as the invoice is still due; the rest stays unapplied (B-1).
        BigDecimal applied = settlements.applyUpToDue(invoiceId, issued.grossAmount());
        if (applied.signum() > 0) {
            links.link(creditNoteId, invoiceId, LinkType.CREDITS, applied, scope);
        }
        jdbc.update(
                "insert into trading.doc_credit_note (document_id, invoice_document_id, claim_document_id, reason)"
                        + " values (?, ?, ?, ?)",
                creditNoteId,
                invoiceId,
                claim.header().id(),
                reason);
        BigDecimal credited = IssueCreditNoteHandler.creditedFromLinks(documents, invoiceId);
        jdbc.update("update trading.doc_invoice set credited_amount = ? where document_id = ?", credited, invoiceId);
        List<Posting> journal = postings.postings(
                IssueCreditNoteHandler.CN,
                "GOODS",
                "SELLER",
                Map.of("net", issued.netAmount(), "tax", issued.taxAmount()));

        Instant decidedAt = clock.now();
        String findings = command.findings() == null || command.findings().isBlank()
                ? null
                : command.findings().strip();
        jdbc.update(
                """
                insert into trading.claim_decision (claim_document_id, decision, findings, return_required,
                    credit_note_document_id, decided_by, decided_at, owner_entity_id, counterparty_entity_id)
                values (?, 'APPROVED', ?, ?, ?, ?, ?, ?, ?)
                """,
                claim.header().id(),
                findings,
                command.returnRequired(),
                creditNoteId,
                scope.userId(),
                Timestamp.from(decidedAt),
                seller,
                buyer);
        for (Map.Entry<UUID, BigDecimal> line : approved.entrySet()) {
            jdbc.update(
                    """
                    insert into trading.claim_decision_line (claim_line_id, claim_document_id, approved_qty,
                        owner_entity_id, counterparty_entity_id)
                    values (?, ?, ?, ?, ?)
                    """,
                    line.getKey(),
                    claim.header().id(),
                    line.getValue(),
                    seller,
                    buyer);
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", ClaimReads.APPROVED);
        after.put("returnRequired", command.returnRequired());
        after.put("creditNoteId", creditNoteId);
        after.put("findings", findings);
        audit.record(
                AUDIT_APPROVED,
                Subject.of("claim", claim.header().id()),
                Map.of("status", ClaimReads.RAISED),
                after,
                scope);
        audit.record(
                IssueCreditNoteHandler.AUDIT_ISSUED,
                Subject.of("credit_note", creditNoteId),
                null,
                IssueCreditNoteHandler.auditAfter(issued, invoiceId, null, credited, applied),
                scope);

        events.publish(new ClaimApproved(
                claim.header().id(),
                claim.grnId(),
                seller,
                buyer,
                command.returnRequired(),
                creditNoteId,
                scope.userId(),
                decidedAt,
                List.copyOf(accepted)));
        events.publish(IssueCreditNoteHandler.issuedEvent(issued, invoiceId, null, seller, buyer));
        events.publish(new JournalPostingsReady(
                creditNoteId, IssueCreditNoteHandler.CN, issued.docNumberDisplay(), seller, journal));
        return creditNoteId;
    }

    /** Every claimed line with its accepted quantity; lines not named are accepted at 0 (all in full when none named). */
    private static Map<UUID, BigDecimal> approvedQuantities(ClaimReads.Claim claim, List<ApproveClaim.Line> chosen) {
        Map<UUID, BigDecimal> approved = new LinkedHashMap<>();
        for (ClaimLine line : claim.lines()) {
            approved.put(line.claimLineId(), chosen.isEmpty() ? line.qty() : BigDecimal.ZERO);
        }
        Set<UUID> seen = new HashSet<>();
        for (ApproveClaim.Line want : chosen) {
            ClaimLine line = claim.lines().stream()
                    .filter(candidate -> want != null && candidate.claimLineId().equals(want.claimLineId()))
                    .findFirst()
                    .orElseThrow(() -> new ProblemException("m4.claim.line_unknown"));
            if (!seen.add(line.claimLineId())) {
                throw new ProblemException("m4.claim.line_unknown");
            }
            BigDecimal qty = want.qty();
            if (qty == null
                    || qty.signum() < 0
                    || qty.compareTo(line.qty()) > 0
                    || qty.stripTrailingZeros().scale() > 3) {
                throw new ProblemException("m4.claim.qty_invalid", Map.of("skuId", line.skuId()));
            }
            approved.put(line.claimLineId(), qty);
        }
        return approved;
    }
}
