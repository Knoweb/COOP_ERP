package lk.coopfed.knoweb.m4trading.internal.invoice;

import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER_USER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.DHAL;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SHOP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.STRANGER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static lk.coopfed.knoweb.m4trading.TradingFixture.seller;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m4trading.TradingFixture;
import lk.coopfed.knoweb.m4trading.TradingFlow;
import lk.coopfed.knoweb.m4trading.api.ApplyCreditNote;
import lk.coopfed.knoweb.m4trading.api.ApproveClaim;
import lk.coopfed.knoweb.m4trading.api.CaptureGrn;
import lk.coopfed.knoweb.m4trading.api.ConfirmGrn;
import lk.coopfed.knoweb.m4trading.api.CreditNoteApplied;
import lk.coopfed.knoweb.m4trading.api.CreditNoteIssued;
import lk.coopfed.knoweb.m4trading.api.DisputeInvoice;
import lk.coopfed.knoweb.m4trading.api.IssueCreditNote;
import lk.coopfed.knoweb.m4trading.api.IssueInvoice;
import lk.coopfed.knoweb.m4trading.api.JournalPostingsReady;
import lk.coopfed.knoweb.m4trading.api.RaiseClaim;
import lk.coopfed.knoweb.m4trading.api.RecordPaymentReceipt;
import lk.coopfed.knoweb.m4trading.api.ResolveInvoiceDispute;
import lk.coopfed.knoweb.m4trading.api.SettleDiscrepancy;
import lk.coopfed.knoweb.m4trading.internal.claim.ApproveClaimHandler;
import lk.coopfed.knoweb.m4trading.internal.claim.RaiseClaimHandler;
import lk.coopfed.knoweb.m4trading.internal.grn.CaptureGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.grn.ConfirmGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.payment.RecordPaymentReceiptHandler;
import lk.coopfed.knoweb.m4trading.query.ClaimQueries;
import lk.coopfed.knoweb.m4trading.query.CreditNoteQueries;
import lk.coopfed.knoweb.m4trading.query.CreditNoteView;
import lk.coopfed.knoweb.m4trading.query.DeliveryQueries;
import lk.coopfed.knoweb.m4trading.query.ExposureQueries;
import lk.coopfed.knoweb.m4trading.query.ExposureView;
import lk.coopfed.knoweb.m4trading.query.GrnQueries;
import lk.coopfed.knoweb.m4trading.query.GrnView;
import lk.coopfed.knoweb.m4trading.query.InvoiceQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceView;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Wave 2, M4MONEY-01, 02, 03, 04, 11 and 13 (docs/progress/deviations/2026-10-06-wave2-credit-once-per-line.md,
 * CR-24A-3 items 1 and 2): a billed unit is credited once, whichever of SettleDiscrepancy,
 * ApproveClaim and IssueCreditNote credits it; a credit note beyond what is due stays unapplied and
 * ApplyCreditNote applies it later; dispute acts are ordered by sequence. Each test was the
 * verifier's reproduction of a finding, turned round: it passed while the finding held, and now
 * asserts the fix.
 *
 * <p>The fixture: 10 rice at 120.00 + 18 % VAT and 4 dhal at 80.00 exempt, invoiced at 1736.00.
 */
@Import(TradingFlow.class)
class CreditOncePerLinePostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    TradingFlow flow;

    @Autowired
    CaptureGrnHandler capture;

    @Autowired
    ConfirmGrnHandler confirm;

    @Autowired
    IssueInvoiceHandler issueInvoice;

    @Autowired
    IssueCreditNoteHandler issueCreditNote;

    @Autowired
    ApplyCreditNoteHandler applyCreditNote;

    @Autowired
    SettleDiscrepancyHandler settle;

    @Autowired
    DisputeInvoiceHandler dispute;

    @Autowired
    ResolveInvoiceDisputeHandler resolve;

    @Autowired
    RaiseClaimHandler raise;

    @Autowired
    ApproveClaimHandler approve;

    @Autowired
    RecordPaymentReceiptHandler record;

    @Autowired
    ClaimQueries claims;

    @Autowired
    GrnQueries grns;

    @Autowired
    DeliveryQueries deliveries;

    @Autowired
    InvoiceQueries invoices;

    @Autowired
    CreditNoteQueries creditNotes;

    @Autowired
    ExposureQueries exposures;

    private UUID grnId;
    private UUID riceGrnLine;
    private UUID discrepancyId;
    private UUID invoiceId;

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    /** 10 rice ({@code riceDamaged} of them damaged) and 4 dhal received and invoiced: 1736.00. */
    private void receive(String riceDamaged) {
        UUID noteId = flow.dispatchedNote(SHOP);
        UUID dropId = deliveries
                .getDeliveryNote(noteId, buyer())
                .orElseThrow()
                .drops()
                .get(0)
                .dropId();
        grnId = capture.handle(
                new CaptureGrn(dropId, SHOP, null, List.of(line(RICE, "10", riceDamaged), line(DHAL, "4", "0"))),
                buyer());
        confirm.handle(new ConfirmGrn(grnId), buyer());
        GrnView grn = grns.getGrn(grnId, buyer()).orElseThrow();
        discrepancyId = grn.discrepancyId();
        riceGrnLine = grn.lines().stream()
                .filter(line -> RICE.equals(line.skuId()))
                .map(GrnView.GrnLineView::lineId)
                .findFirst()
                .orElseThrow();
        invoiceId = issueInvoice.handle(new IssueInvoice(List.of(grnId)), seller());
        kernel.reset();
    }

    private UUID invoiceLine(UUID sku) {
        InvoiceView invoice = invoices.getInvoice(invoiceId, seller()).orElseThrow();
        return invoice.lines().stream()
                .filter(line -> line.skuId().equals(sku))
                .findFirst()
                .orElseThrow()
                .lineId();
    }

    private UUID raiseRice(String qty) {
        return raise.handle(
                new RaiseClaim(
                        grnId,
                        "DAMAGED",
                        false,
                        "Crushed",
                        List.of(new RaiseClaim.Line(riceGrnLine, new BigDecimal(qty)))),
                buyer());
    }

    private UUID creditRice(String qty) {
        return issueCreditNote.handle(
                new IssueCreditNote(
                        invoiceId,
                        List.of(new IssueCreditNote.Line(invoiceLine(RICE), new BigDecimal(qty))),
                        "Goodwill"),
                seller());
    }

    /** The rice units credited on the invoice, from every issued credit note line referencing it. */
    private BigDecimal riceCredited() {
        return superuserJdbc()
                .queryForObject(
                        """
                        select coalesce(sum(cl.qty), 0) from kernel.document_line cl
                          join kernel.document_line il on il.document_line_id = cl.reference_line_id
                         where il.document_id = ? and il.sku_id = ?
                        """,
                        BigDecimal.class,
                        invoiceId,
                        RICE);
    }

    // M4MONEY-01, the settlement first: the GRN's 2 damaged rice are the discrepancy's to credit;
    // a claim covers only the 8 the GRN did not record as damaged (decision A-1).
    @Test
    void theSettlementCreditsTheDamagedAndAClaimOnlyTheRest() {
        receive("2");
        settle.handle(new SettleDiscrepancy(discrepancyId, "Two bags damaged"), seller());
        assertThat(riceCredited()).isEqualByComparingTo("2");

        refused(() -> raiseRice("9"), "m4.claim.exceeds_received");
        UUID claimId = raiseRice("8");
        kernel.reset();
        UUID creditNoteId = approve.handle(new ApproveClaim(claimId, null, false, List.of()), seller());

        assertThat(creditNotes
                        .getCreditNote(creditNoteId, seller())
                        .orElseThrow()
                        .grossAmount())
                .isEqualByComparingTo("1132.80");
        assertThat(riceCredited()).isEqualByComparingTo("10");
        // 283.20 + 1132.80: the rice line's 1416.00, never more.
        assertThat(invoices.balance(invoiceId, seller()).orElseThrow().creditedAmount())
                .isEqualByComparingTo("1416.00");
        assertThat(kernel.committedAudit())
                .extracting(audit -> audit.eventType())
                .contains("CLAIM_APPROVED", "CREDIT_NOTE_ISSUED");

        kernel.reset();
        refused(() -> creditRice("1"), "m4.creditnote.exceeds_billed");
        assertThat(kernel.committedEvents()).isEmpty();
    }

    // M4MONEY-01, a credit first: what an earlier credit note took leaves the settlement and the
    // claim only the remainder; the claim's approval is refused at the second act, not capped.
    @Test
    void anEarlierCreditLeavesTheSettlementAndTheClaimOnlyTheRemainder() {
        receive("2");
        creditRice("7");

        // 2 damaged, but only 3 are left on the line: the settlement credits 2, the claim 1.
        UUID settled = settle.handle(new SettleDiscrepancy(discrepancyId, "Two bags damaged"), seller());
        assertThat(creditNotes.getCreditNote(settled, seller()).orElseThrow().lines())
                .singleElement()
                .satisfies(line -> assertThat(line.qty()).isEqualByComparingTo("2"));
        UUID claimId = raiseRice("8");
        UUID claimLine =
                claims.getClaim(claimId, seller()).orElseThrow().lines().get(0).claimLineId();

        kernel.reset();
        assertThatThrownBy(() -> approve.handle(new ApproveClaim(claimId, null, false, List.of()), seller()))
                .isInstanceOf(ProblemException.class)
                .satisfies(error -> {
                    ProblemException problem = (ProblemException) error;
                    assertThat(problem.messageId()).isEqualTo("m4.creditnote.exceeds_billed");
                    assertThat(problem.parameters())
                            .containsEntry("billed", "10.000")
                            .containsEntry("credited", "9.000")
                            .containsEntry("remaining", "1.000");
                });
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        approve.handle(
                new ApproveClaim(claimId, null, false, List.of(new ApproveClaim.Line(claimLine, BigDecimal.ONE))),
                seller());
        assertThat(riceCredited()).isEqualByComparingTo("10");
        assertThat(claims.getClaim(claimId, buyer())
                        .orElseThrow()
                        .lines()
                        .get(0)
                        .approvedQty())
                .isEqualByComparingTo("1");
    }

    // M4MONEY-01, the claim first: the settlement credits only what the claim left.
    @Test
    void anApprovedClaimLeavesTheSettlementOnlyWhatIsLeft() {
        receive("2");
        creditRice("1");
        UUID claimId = raiseRice("8");
        approve.handle(new ApproveClaim(claimId, null, false, List.of()), seller());

        UUID settled = settle.handle(new SettleDiscrepancy(discrepancyId, "Two bags damaged"), seller());

        assertThat(creditNotes.getCreditNote(settled, seller()).orElseThrow().lines())
                .singleElement()
                .satisfies(line -> assertThat(line.qty()).isEqualByComparingTo("1"));
        assertThat(riceCredited()).isEqualByComparingTo("10");
        assertThat(invoices.balance(invoiceId, seller()).orElseThrow().creditedAmount())
                .isEqualByComparingTo("1416.00");

        // Nothing is left on the line at all: a further settlement would settle with no money.
        // (A discrepancy settles once; this checks the cap through a new credit note instead.)
        refused(() -> creditRice("0.001"), "m4.creditnote.exceeds_billed");
    }

    // M4MONEY-02: a line named twice, and two credit notes that together exceed a line.
    @Test
    void aLineIsNamedOnceAndTwoCreditNotesNeverExceedIt() {
        receive("0");
        UUID dhal = invoiceLine(DHAL);
        refused(
                () -> issueCreditNote.handle(
                        new IssueCreditNote(
                                invoiceId,
                                List.of(
                                        new IssueCreditNote.Line(dhal, BigDecimal.TWO),
                                        new IssueCreditNote.Line(dhal, BigDecimal.TWO)),
                                "x"),
                        seller()),
                "m4.creditnote.line_duplicate");
        assertThat(kernel.committedEvents()).isEmpty();

        issueCreditNote.handle(
                new IssueCreditNote(invoiceId, List.of(new IssueCreditNote.Line(dhal, new BigDecimal("3"))), "x"),
                seller());
        refused(
                () -> issueCreditNote.handle(
                        new IssueCreditNote(invoiceId, List.of(new IssueCreditNote.Line(dhal, BigDecimal.TWO)), "y"),
                        seller()),
                "m4.creditnote.exceeds_billed");
        issueCreditNote.handle(
                new IssueCreditNote(invoiceId, List.of(new IssueCreditNote.Line(dhal, BigDecimal.ONE)), "y"), seller());
        // 4 dhal at 80.00, exempt: 320.00 and not a rupee more.
        assertThat(invoices.balance(invoiceId, seller()).orElseThrow().creditedAmount())
                .isEqualByComparingTo("320.00");
    }

    // M4MONEY-04: 0.0004 would be stored as 0.000 with money credited.
    @Test
    void aQuantityOfMoreThanThreeDecimalsIsRefused() {
        receive("0");
        refused(() -> creditRice("0.0004"), "m4.creditnote.qty_invalid");
        assertThat(kernel.committedAudit()).isEmpty();
        creditRice("0.001");
        assertThat(riceCredited()).isEqualByComparingTo("0.001");
    }

    // M4MONEY-13 (decision A-2): a partial approval decides the approved quantity only.
    @Test
    void aPartialApprovalFreesTheRestToClaimAgain() {
        receive("0");
        UUID claimId = raiseRice("10");
        UUID claimLine =
                claims.getClaim(claimId, seller()).orElseThrow().lines().get(0).claimLineId();
        approve.handle(
                new ApproveClaim(claimId, null, false, List.of(new ApproveClaim.Line(claimLine, BigDecimal.TWO))),
                seller());

        refused(() -> raiseRice("9"), "m4.claim.exceeds_received");
        UUID again = raiseRice("8");
        assertThat(claims.getClaim(again, buyer()).orElseThrow().lines().get(0).claimedQty())
                .isEqualByComparingTo("8");
        // The undecided re-claim of 8 holds them: nothing is left to claim now.
        refused(() -> raiseRice("1"), "m4.claim.exceeds_received");
    }

    // M4MONEY-03 (decision B-1): a credit beyond what is due is issued in full and stays
    // unapplied; ApplyCreditNote applies it to another invoice of the pair.
    @Test
    void aCreditAfterFullPaymentStaysUnappliedAndIsAppliedLater() {
        receive("2");
        UUID paid = invoiceId;
        record.handle(
                new RecordPaymentReceipt(BUYER, "CASH", new BigDecimal("1736.00"), null, null, null, List.of()),
                seller());
        kernel.reset();

        UUID creditNoteId = settle.handle(new SettleDiscrepancy(discrepancyId, "Two bags damaged"), seller());

        CreditNoteView note = creditNotes.getCreditNote(creditNoteId, buyer()).orElseThrow();
        assertThat(note.grossAmount()).isEqualByComparingTo("283.20");
        assertThat(note.appliedAmount()).isEqualByComparingTo("0");
        assertThat(note.unappliedAmount()).isEqualByComparingTo("283.20");
        assertThat(invoices.balance(paid, seller()).orElseThrow().creditedAmount())
                .isEqualByComparingTo("0");
        assertThat(kernel.committedAudit())
                .filteredOn(audit -> audit.eventType().equals("CREDIT_NOTE_ISSUED"))
                .singleElement()
                .satisfies(audit -> assertThat(((Map<?, ?>) audit.after()).get("unappliedAmount"))
                        .isEqualTo(new BigDecimal("283.20")));
        assertThat(events(CreditNoteIssued.class)).hasSize(1);
        // The seller's books are the same either way: revenue and VAT reversed, receivable credited.
        assertThat(events(JournalPostingsReady.class)).singleElement().satisfies(event -> assertThat(event.postings())
                .extracting(posting -> posting.debitRole() + "/" + posting.creditRole() + "="
                        + posting.amount().toPlainString())
                .containsExactlyInAnyOrder("REVENUE/RECEIVABLE=240.00", "VAT_OUTPUT/RECEIVABLE=43.20"));
        ExposureView exposure = exposures.exposure(SELLER, BUYER, seller()).orElseThrow();
        assertThat(exposure.unappliedCredits()).isEqualByComparingTo("283.20");
        assertThat(exposure.amount()).isEqualByComparingTo("-283.20");

        // A one-unit claim after payment is credited the same way: issued, unapplied.
        UUID claimId = raiseRice("1");
        UUID claimed = approve.handle(new ApproveClaim(claimId, null, false, List.of()), seller());
        assertThat(creditNotes.getCreditNote(claimed, seller()).orElseThrow().unappliedAmount())
                .isEqualByComparingTo("141.60");

        // A second invoice of the pair: the credit is applied to it.
        receive("0");
        UUID open = invoiceId;
        refused(
                () -> applyCreditNote.handle(new ApplyCreditNote(creditNoteId, open, null), buyer()),
                "m4.creditnote.not_seller");
        refused(
                () -> applyCreditNote.handle(new ApplyCreditNote(UUID.randomUUID(), open, null), seller()),
                "m4.creditnote.not_found");
        refused(
                () -> applyCreditNote.handle(new ApplyCreditNote(creditNoteId, UUID.randomUUID(), null), seller()),
                "m4.invoice.not_found");
        refused(
                () -> applyCreditNote.handle(new ApplyCreditNote(creditNoteId, paid, null), seller()),
                "m4.creditnote.exceeds_due");
        refused(
                () -> applyCreditNote.handle(
                        new ApplyCreditNote(creditNoteId, open, new BigDecimal("0.001")), seller()),
                "m4.creditnote.amount_invalid");
        refused(
                () -> applyCreditNote.handle(
                        new ApplyCreditNote(creditNoteId, open, new BigDecimal("283.21")), seller()),
                "m4.creditnote.exceeds_unapplied");
        dispute.handle(new DisputeInvoice(open, "Wrong price"), buyer());
        refused(
                () -> applyCreditNote.handle(new ApplyCreditNote(creditNoteId, open, null), seller()),
                "m4.creditnote.invoice_disputed");
        resolve.handle(new ResolveInvoiceDispute(open, null), seller());
        assertThat(kernel.committedEvents()).noneMatch(CreditNoteApplied.class::isInstance);

        kernel.reset();
        BigDecimal applied =
                applyCreditNote.handle(new ApplyCreditNote(creditNoteId, open, new BigDecimal("100.00")), seller());

        assertThat(applied).isEqualByComparingTo("100.00");
        assertThat(creditNotes.getCreditNote(creditNoteId, seller()).orElseThrow())
                .satisfies(applying -> {
                    assertThat(applying.appliedAmount()).isEqualByComparingTo("100.00");
                    assertThat(applying.unappliedAmount()).isEqualByComparingTo("183.20");
                });
        assertThat(invoices.balance(open, buyer()).orElseThrow().amountDue()).isEqualByComparingTo("1636.00");
        assertThat(kernel.committedAudit())
                .extracting(audit -> audit.eventType())
                .contains("CREDIT_NOTE_APPLIED");
        assertThat(events(CreditNoteApplied.class)).singleElement().satisfies(event -> {
            assertThat(event.invoiceId()).isEqualTo(open);
            assertThat(event.buyerEntityId()).isEqualTo(BUYER);
            assertThat(event.appliedAmount()).isEqualByComparingTo("100.00");
            assertThat(event.unappliedAmount()).isEqualByComparingTo("183.20");
        });
        assertThat(events(JournalPostingsReady.class)).isEmpty();
        // 1736.00 open on the second invoice, less 100.00, less the two credits still unapplied.
        assertThat(exposures.exposure(SELLER, BUYER, seller()).orElseThrow()).satisfies(after -> {
            assertThat(after.unappliedCredits()).isEqualByComparingTo("324.80");
            assertThat(after.amount()).isEqualByComparingTo("1311.20");
        });

        // A link is keyed on its two documents: a credit note is applied to an invoice once.
        refused(
                () -> applyCreditNote.handle(new ApplyCreditNote(creditNoteId, open, null), seller()),
                "m4.creditnote.applied_already");
        // With no amount, the claim's credit note applies as much as fits.
        assertThat(applyCreditNote.handle(new ApplyCreditNote(claimed, open, null), seller()))
                .isEqualByComparingTo("141.60");
    }

    // B-1 in part: a part-paid invoice takes what is due, the rest stays on the credit note.
    @Test
    void aCreditOnAPartPaidInvoiceAppliesWhatIsDue() {
        receive("2");
        record.handle(
                new RecordPaymentReceipt(BUYER, "CASH", new BigDecimal("1636.00"), null, null, null, List.of()),
                seller());

        UUID creditNoteId = settle.handle(new SettleDiscrepancy(discrepancyId, "Two bags damaged"), seller());

        assertThat(creditNotes.getCreditNote(creditNoteId, seller()).orElseThrow())
                .satisfies(note -> {
                    assertThat(note.appliedAmount()).isEqualByComparingTo("100.00");
                    assertThat(note.unappliedAmount()).isEqualByComparingTo("183.20");
                });
        assertThat(invoices.balance(invoiceId, seller()).orElseThrow().amountDue())
                .isEqualByComparingTo("0");
    }

    @Test
    void applyCreditNoteRefusesAnInvoiceOfAnotherPair() {
        receive("2");
        record.handle(
                new RecordPaymentReceipt(BUYER, "CASH", new BigDecimal("1736.00"), null, null, null, List.of()),
                seller());
        UUID creditNoteId = settle.handle(new SettleDiscrepancy(discrepancyId, "Two bags damaged"), seller());
        receive("0");
        UUID other = invoiceId;
        // The second invoice made out to another buyer, as the seller would see one: the document
        // rows refuse an update, so the trigger is set aside for this one statement.
        superuserJdbc()
                .execute("begin; set local session_replication_role = replica;"
                        + " update kernel.document set counterparty_entity_id = '" + STRANGER
                        + "' where document_id = '" + other + "'; commit;");

        refused(
                () -> applyCreditNote.handle(new ApplyCreditNote(creditNoteId, other, null), seller()),
                "m4.creditnote.invoice_not_ours");
        assertThat(kernel.committedEvents()).noneMatch(CreditNoteApplied.class::isInstance);
    }

    // M4MONEY-11: the latest dispute act is the one with the highest sequence, whatever the
    // writing instance's clock said.
    @Test
    void theLatestDisputeActIsTheLastWrittenNotTheLatestClock() {
        receive("0");
        Instant now = Instant.now();
        insertDisputeAct("DISPUTED", now);
        assertThat(invoices.balance(invoiceId, seller()).orElseThrow().disputed())
                .isTrue();
        // Written later by an instance whose clock is two seconds behind.
        insertDisputeAct("RESOLVED", now.minusSeconds(2));

        assertThat(invoices.balance(invoiceId, seller()).orElseThrow().disputed())
                .isFalse();
        assertThat(invoices.balance(invoiceId, buyer()).orElseThrow().disputed())
                .isFalse();
    }

    private void insertDisputeAct(String action, Instant at) {
        superuserJdbc()
                .update(
                        """
                        insert into trading.invoice_dispute (dispute_event_id, invoice_document_id, action, reason,
                            actor_user_id, recorded_at, owner_entity_id, counterparty_entity_id)
                        values (?, ?, ?, 'test', ?, ?, ?, ?)
                        """,
                        UUID.randomUUID(),
                        invoiceId,
                        action,
                        BUYER_USER,
                        Timestamp.from(at),
                        BUYER,
                        SELLER);
    }

    private static CaptureGrn.Line line(UUID sku, String received, String damaged) {
        return new CaptureGrn.Line(
                sku, "EA", new BigDecimal(received), new BigDecimal(damaged), null, null, null, null);
    }

    private static void refused(ThrowingCallable call, String messageId) {
        assertThatThrownBy(call).isInstanceOf(ProblemException.class).satisfies(error -> assertThat(
                        ((ProblemException) error).messageId())
                .isEqualTo(messageId));
    }

    private <E extends DomainEvent> List<E> events(Class<E> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }
}
