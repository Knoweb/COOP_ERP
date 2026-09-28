package lk.coopfed.knoweb.m4trading.internal.invoice;

import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.DHAL;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SHOP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static lk.coopfed.knoweb.m4trading.TradingFixture.seller;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m4trading.TradingFixture;
import lk.coopfed.knoweb.m4trading.TradingFlow;
import lk.coopfed.knoweb.m4trading.api.CaptureGrn;
import lk.coopfed.knoweb.m4trading.api.ConfirmGrn;
import lk.coopfed.knoweb.m4trading.api.CreditNoteIssued;
import lk.coopfed.knoweb.m4trading.api.CreditNotePrinted;
import lk.coopfed.knoweb.m4trading.api.DisputeInvoice;
import lk.coopfed.knoweb.m4trading.api.InvoiceDisputeResolved;
import lk.coopfed.knoweb.m4trading.api.InvoiceDisputed;
import lk.coopfed.knoweb.m4trading.api.IssueCreditNote;
import lk.coopfed.knoweb.m4trading.api.IssueInvoice;
import lk.coopfed.knoweb.m4trading.api.JournalPostingsReady;
import lk.coopfed.knoweb.m4trading.api.RecordCreditNotePrint;
import lk.coopfed.knoweb.m4trading.api.ResolveInvoiceDispute;
import lk.coopfed.knoweb.m4trading.internal.grn.CaptureGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.grn.ConfirmGrnHandler;
import lk.coopfed.knoweb.m4trading.query.CreditNoteQueries;
import lk.coopfed.knoweb.m4trading.query.CreditNoteView;
import lk.coopfed.knoweb.m4trading.query.DeliveryQueries;
import lk.coopfed.knoweb.m4trading.query.DiscrepancyQueries;
import lk.coopfed.knoweb.m4trading.query.DiscrepancyView;
import lk.coopfed.knoweb.m4trading.query.GrnQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceBalance;
import lk.coopfed.knoweb.m4trading.query.InvoiceQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceView;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * The rest of M4-08 the demo needs (24A section 6): a short delivery is settled with a credit note
 * (IssueCreditNote with the discrepancy), a credit note of chosen lines, the invoice's credited
 * amount and amount due, DisputeInvoice and ResolveInvoiceDispute, RecordCreditNotePrint; every
 * guard, and what each audits and publishes.
 */
@Import(TradingFlow.class)
class CreditNoteHandlersPostgresIntegrationTest extends PostgresIntegrationTest {

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
    DisputeInvoiceHandler dispute;

    @Autowired
    ResolveInvoiceDisputeHandler resolve;

    @Autowired
    RecordCreditNotePrintHandler print;

    @Autowired
    InvoiceQueries invoices;

    @Autowired
    CreditNoteQueries creditNotes;

    @Autowired
    DiscrepancyQueries discrepancies;

    @Autowired
    GrnQueries grns;

    @Autowired
    DeliveryQueries deliveries;

    private UUID grnId;
    private UUID discrepancyId;
    private UUID invoiceId;

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());
        UUID noteId = flow.dispatchedNote(SHOP);
        UUID dropId = deliveries
                .getDeliveryNote(noteId, buyer())
                .orElseThrow()
                .drops()
                .get(0)
                .dropId();
        // 8 rice of 10 arrive (short by 2), the 4 dhal in full; the buyer confirms, the seller invoices.
        grnId = capture.handle(new CaptureGrn(dropId, SHOP, null, List.of(line(RICE, "8"), line(DHAL, "4"))), buyer());
        confirm.handle(new ConfirmGrn(grnId), buyer());
        discrepancyId = grns.getGrn(grnId, buyer()).orElseThrow().discrepancyId();
        invoiceId = issueInvoice.handle(new IssueInvoice(List.of(grnId)), seller());
        kernel.reset();
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    @Test
    void theSellerSettlesTheShortDeliveryWithACreditNoteAndBothPartiesSeeIt() {
        DiscrepancyView raised =
                discrepancies.getDiscrepancy(discrepancyId, seller()).orElseThrow();
        assertThat(raised.status()).isEqualTo(DiscrepancyView.RAISED);
        assertThat(raised.kind()).isEqualTo("SHORT");
        assertThat(raised.invoiceId()).isEqualTo(invoiceId);
        assertThat(raised.lines()).singleElement().satisfies(line -> {
            assertThat(line.skuId()).isEqualTo(RICE);
            assertThat(line.varianceQty()).isEqualByComparingTo("-2");
        });
        assertThat(discrepancies.listDiscrepancies(OrderQueries.Role.SELLER, seller()))
                .extracting(DiscrepancyView::discrepancyId)
                .containsExactly(discrepancyId);
        assertThat(discrepancies.listDiscrepancies(OrderQueries.Role.BUYER, buyer()))
                .extracting(DiscrepancyView::discrepancyId)
                .containsExactly(discrepancyId);

        UUID creditNoteId = issueCreditNote.handle(
                new IssueCreditNote(invoiceId, discrepancyId, List.of(), "Two bags short on delivery"), seller());

        // 2 rice at the invoice's 120.00 = 240.00, VAT 18 % = 43.20.
        CreditNoteView note = creditNotes.getCreditNote(creditNoteId, buyer()).orElseThrow();
        assertThat(note.docNumberDisplay()).isEqualTo("D4S-CN-0000001");
        assertThat(note.invoiceId()).isEqualTo(invoiceId);
        assertThat(note.discrepancyId()).isEqualTo(discrepancyId);
        assertThat(note.netAmount()).isEqualByComparingTo("240.00");
        assertThat(note.taxAmount()).isEqualByComparingTo("43.20");
        assertThat(note.grossAmount()).isEqualByComparingTo("283.20");
        assertThat(note.lines()).singleElement().satisfies(line -> {
            assertThat(line.skuId()).isEqualTo(RICE);
            assertThat(line.qty()).isEqualByComparingTo("2");
            assertThat(line.unitPrice()).isEqualByComparingTo("120");
        });

        // The buyer sees the discrepancy settled by it, and the invoice's amount due net of it.
        DiscrepancyView settled =
                discrepancies.getDiscrepancy(discrepancyId, buyer()).orElseThrow();
        assertThat(settled.status()).isEqualTo(DiscrepancyView.SETTLED);
        assertThat(settled.creditNoteId()).isEqualTo(creditNoteId);
        assertThat(settled.creditNoteDocNumberDisplay()).isEqualTo("D4S-CN-0000001");
        InvoiceBalance balance = invoices.balance(invoiceId, buyer()).orElseThrow();
        assertThat(balance.creditedAmount()).isEqualByComparingTo("283.20");
        assertThat(balance.amountDue()).isEqualByComparingTo("1169.60");
        assertThat(creditNotes.creditNotesOf(invoiceId, buyer()))
                .extracting(CreditNoteView::creditNoteId)
                .containsExactly(creditNoteId);

        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .contains("DOCUMENT_ISSUED", "CREDIT_NOTE_ISSUED");
        assertThat(events(CreditNoteIssued.class)).singleElement().satisfies(event -> {
            assertThat(event.invoiceId()).isEqualTo(invoiceId);
            assertThat(event.discrepancyId()).isEqualTo(discrepancyId);
            assertThat(event.buyerEntityId()).isEqualTo(BUYER);
            assertThat(event.grossAmount()).isEqualByComparingTo("283.20");
            assertThat(event.contentHash()).hasSize(64);
        });
        assertThat(events(JournalPostingsReady.class)).singleElement().satisfies(event -> {
            assertThat(event.ownerEntityId()).isEqualTo(SELLER);
            assertThat(event.postings())
                    .extracting(posting -> posting.debitRole() + "/" + posting.creditRole() + "="
                            + posting.amount().toPlainString())
                    .containsExactlyInAnyOrder("REVENUE/RECEIVABLE=240.00", "VAT_OUTPUT/RECEIVABLE=43.20");
        });

        kernel.reset();
        refused(
                () -> issueCreditNote.handle(
                        new IssueCreditNote(invoiceId, discrepancyId, List.of(), "again"), seller()),
                "m4.creditnote.discrepancy_settled");
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void theGuardsOfIssueCreditNote() {
        refused(
                () -> issueCreditNote.handle(new IssueCreditNote(invoiceId, discrepancyId, List.of(), "x"), buyer()),
                "m4.creditnote.not_seller");
        refused(
                () -> issueCreditNote.handle(
                        new IssueCreditNote(UUID.randomUUID(), discrepancyId, List.of(), "x"), seller()),
                "m4.invoice.not_found");
        refused(
                () -> issueCreditNote.handle(new IssueCreditNote(invoiceId, discrepancyId, List.of(), " "), seller()),
                "request.field.required");
        refused(
                () -> issueCreditNote.handle(new IssueCreditNote(invoiceId, null, List.of(), "x"), seller()),
                "m4.creditnote.discrepancy_or_lines");
        UUID someLine = invoices.getInvoice(invoiceId, seller())
                .orElseThrow()
                .lines()
                .get(0)
                .lineId();
        refused(
                () -> issueCreditNote.handle(
                        new IssueCreditNote(
                                invoiceId,
                                discrepancyId,
                                List.of(new IssueCreditNote.Line(someLine, BigDecimal.ONE)),
                                "x"),
                        seller()),
                "m4.creditnote.discrepancy_or_lines");
        refused(
                () -> issueCreditNote.handle(
                        new IssueCreditNote(invoiceId, UUID.randomUUID(), List.of(), "x"), seller()),
                "m4.discrepancy.not_found");
        refused(
                () -> issueCreditNote.handle(
                        new IssueCreditNote(
                                invoiceId,
                                null,
                                List.of(new IssueCreditNote.Line(UUID.randomUUID(), BigDecimal.ONE)),
                                "x"),
                        seller()),
                "m4.creditnote.line_unknown");
        refused(
                () -> issueCreditNote.handle(
                        new IssueCreditNote(
                                invoiceId,
                                null,
                                List.of(new IssueCreditNote.Line(someLine, new BigDecimal("999"))),
                                "x"),
                        seller()),
                "m4.creditnote.qty_invalid");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void aCreditNoteOfChosenLinesAndTheInvoicesOpenBalance() {
        InvoiceView invoice = invoices.getInvoice(invoiceId, seller()).orElseThrow();
        InvoiceView.InvoiceLineView dhal = invoice.lines().stream()
                .filter(line -> line.skuId().equals(DHAL))
                .findFirst()
                .orElseThrow();

        // One dhal back at 80.00, EXEMPT.
        UUID creditNoteId = issueCreditNote.handle(
                new IssueCreditNote(
                        invoiceId, null, List.of(new IssueCreditNote.Line(dhal.lineId(), BigDecimal.ONE)), "Torn bag"),
                seller());

        assertThat(creditNotes
                        .getCreditNote(creditNoteId, seller())
                        .orElseThrow()
                        .grossAmount())
                .isEqualByComparingTo("80.00");
        assertThat(invoices.balance(invoiceId, seller()).orElseThrow().amountDue())
                .isEqualByComparingTo("1372.80");
        assertThat(events(CreditNoteIssued.class)).singleElement().satisfies(event -> assertThat(event.discrepancyId())
                .isNull());
    }

    @Test
    void theBuyerDisputesTheInvoiceAndEitherPartyResolvesIt() {
        refused(() -> dispute.handle(new DisputeInvoice(invoiceId, "Short"), seller()), "m4.invoice.not_buyer");
        refused(() -> dispute.handle(new DisputeInvoice(invoiceId, ""), buyer()), "request.field.required");
        refused(() -> dispute.handle(new DisputeInvoice(UUID.randomUUID(), "x"), buyer()), "m4.invoice.not_found");
        refused(() -> resolve.handle(new ResolveInvoiceDispute(invoiceId, null), seller()), "m4.invoice.not_disputed");
        assertThat(kernel.committedEvents()).isEmpty();

        dispute.handle(new DisputeInvoice(invoiceId, "Two bags of rice short"), buyer());

        assertThat(invoices.balance(invoiceId, seller()).orElseThrow()).satisfies(balance -> {
            assertThat(balance.disputed()).isTrue();
            assertThat(balance.disputeReason()).isEqualTo("Two bags of rice short");
        });
        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .containsExactly("INVOICE_DISPUTED");
        assertThat(events(InvoiceDisputed.class)).singleElement().satisfies(event -> {
            assertThat(event.sellerEntityId()).isEqualTo(SELLER);
            assertThat(event.buyerEntityId()).isEqualTo(BUYER);
        });
        refused(() -> dispute.handle(new DisputeInvoice(invoiceId, "again"), buyer()), "m4.invoice.disputed_already");

        kernel.reset();
        resolve.handle(new ResolveInvoiceDispute(invoiceId, "Credit note issued"), seller());

        assertThat(invoices.balance(invoiceId, buyer()).orElseThrow().disputed())
                .isFalse();
        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .containsExactly("INVOICE_DISPUTE_RESOLVED");
        assertThat(events(InvoiceDisputeResolved.class))
                .singleElement()
                .satisfies(event -> assertThat(event.resolvedByEntityId()).isEqualTo(SELLER));

        // The buyer may dispute again, and close its own dispute.
        dispute.handle(new DisputeInvoice(invoiceId, "Still short"), buyer());
        resolve.handle(new ResolveInvoiceDispute(invoiceId, null), buyer());
        assertThat(invoices.balance(invoiceId, seller()).orElseThrow().disputed())
                .isFalse();
    }

    @Test
    void thePrintedCopyIsKeptOnTheSellersCreditNote() {
        UUID creditNoteId = issueCreditNote.handle(
                new IssueCreditNote(invoiceId, discrepancyId, List.of(), "Two bags short"), seller());
        kernel.reset();
        String key = "reports/" + SELLER + "/" + UUID.randomUUID() + ".pdf";

        assertThatThrownBy(() -> print.handle(new RecordCreditNotePrint(creditNoteId, key), buyer()))
                .isInstanceOf(ProblemException.class);
        refused(
                () -> print.handle(new RecordCreditNotePrint(UUID.randomUUID(), key), seller()),
                "m4.creditnote.not_found");
        assertThat(creditNotes.printObjectKey(creditNoteId, seller())).isEmpty();

        print.handle(new RecordCreditNotePrint(creditNoteId, key), seller());

        assertThat(creditNotes.printObjectKey(creditNoteId, buyer())).contains(key);
        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .containsExactly("CREDIT_NOTE_PRINTED");
        assertThat(events(CreditNotePrinted.class)).singleElement().satisfies(event -> assertThat(event.objectKey())
                .isEqualTo(key));
    }

    private static CaptureGrn.Line line(UUID sku, String received) {
        return new CaptureGrn.Line(sku, "EA", new BigDecimal(received), BigDecimal.ZERO, null, null, null, null);
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
