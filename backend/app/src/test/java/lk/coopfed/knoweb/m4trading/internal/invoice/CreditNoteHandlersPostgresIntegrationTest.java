package lk.coopfed.knoweb.m4trading.internal.invoice;

import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.DHAL;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER_USER;
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
import lk.coopfed.knoweb.m4trading.api.DiscrepancySettled;
import lk.coopfed.knoweb.m4trading.api.DisputeInvoice;
import lk.coopfed.knoweb.m4trading.api.InvoiceDisputeResolved;
import lk.coopfed.knoweb.m4trading.api.InvoiceDisputed;
import lk.coopfed.knoweb.m4trading.api.IssueCreditNote;
import lk.coopfed.knoweb.m4trading.api.IssueInvoice;
import lk.coopfed.knoweb.m4trading.api.JournalPostingsReady;
import lk.coopfed.knoweb.m4trading.api.RecordCreditNotePrint;
import lk.coopfed.knoweb.m4trading.api.ResolveInvoiceDispute;
import lk.coopfed.knoweb.m4trading.api.SettleDiscrepancy;
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
 * Settling a discrepancy and the credit notes of M4-08 (24A section 6; the architect's decision of
 * 28 Sep): the invoice bills the RECEIVED quantity, so a short quantity is settled with no money and
 * only damaged quantity the invoice charged is credited, never the shortage twice. Also a credit
 * note of chosen lines, DisputeInvoice and ResolveInvoiceDispute, RecordCreditNotePrint; every
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
    SettleDiscrepancyHandler settle;

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
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    /** 10 rice and 4 dhal were sent; the buyer counts rice as received (and damaged), dhal in full. */
    private void receive(String rice, String riceDamaged, boolean invoice) {
        UUID noteId = flow.dispatchedNote(SHOP);
        UUID dropId = deliveries
                .getDeliveryNote(noteId, buyer())
                .orElseThrow()
                .drops()
                .get(0)
                .dropId();
        grnId = capture.handle(
                new CaptureGrn(dropId, SHOP, null, List.of(line(RICE, rice, riceDamaged), line(DHAL, "4", "0"))),
                buyer());
        confirm.handle(new ConfirmGrn(grnId), buyer());
        discrepancyId = grns.getGrn(grnId, buyer()).orElseThrow().discrepancyId();
        if (invoice) {
            invoiceId = issueInvoice.handle(new IssueInvoice(List.of(grnId)), seller());
        }
        kernel.reset();
    }

    @Test
    void aShortDeliveryIsSettledWithNoMoneyBecauseTheShortageWasNeverBilled() {
        receive("8", "0", true);
        DiscrepancyView raised =
                discrepancies.getDiscrepancy(discrepancyId, seller()).orElseThrow();
        assertThat(raised.status()).isEqualTo(DiscrepancyView.RAISED);
        assertThat(raised.kind()).isEqualTo("SHORT");
        assertThat(raised.invoiceId()).isEqualTo(invoiceId);
        assertThat(discrepancies.listDiscrepancies(OrderQueries.Role.SELLER, seller()))
                .extracting(DiscrepancyView::discrepancyId)
                .containsExactly(discrepancyId);
        assertThat(discrepancies.listDiscrepancies(OrderQueries.Role.BUYER, buyer()))
                .extracting(DiscrepancyView::discrepancyId)
                .containsExactly(discrepancyId);
        // The invoice billed 8 rice, not 10: 960.00 + 18 % + 320.00 dhal.
        assertThat(invoices.getInvoice(invoiceId, seller()).orElseThrow().grossAmount())
                .isEqualByComparingTo("1452.80");

        UUID creditNoteId = settle.handle(new SettleDiscrepancy(discrepancyId, "Count accepted"), seller());

        assertThat(creditNoteId).isNull();
        DiscrepancyView settled =
                discrepancies.getDiscrepancy(discrepancyId, buyer()).orElseThrow();
        assertThat(settled.status()).isEqualTo(DiscrepancyView.SETTLED);
        assertThat(settled.creditNoteId()).isNull();
        assertThat(settled.settledByUserId()).isEqualTo(SELLER_USER);
        assertThat(settled.settledAt()).isNotNull();
        assertThat(settled.settlementReason()).isEqualTo("Count accepted");
        // No double credit: nothing is taken off an invoice that never charged the shortage.
        InvoiceBalance balance = invoices.balance(invoiceId, buyer()).orElseThrow();
        assertThat(balance.creditedAmount()).isEqualByComparingTo("0");
        assertThat(balance.amountDue()).isEqualByComparingTo("1452.80");
        assertThat(creditNotes.creditNotesOf(invoiceId, buyer())).isEmpty();

        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .containsExactly("DISCREPANCY_SETTLED");
        assertThat(events(DiscrepancySettled.class)).singleElement().satisfies(event -> {
            assertThat(event.creditNoteId()).isNull();
            assertThat(event.buyerEntityId()).isEqualTo(BUYER);
            assertThat(event.settledByUserId()).isEqualTo(SELLER_USER);
        });
        assertThat(events(CreditNoteIssued.class)).isEmpty();
        assertThat(events(JournalPostingsReady.class)).isEmpty();

        kernel.reset();
        refused(
                () -> settle.handle(new SettleDiscrepancy(discrepancyId, "again"), seller()),
                "m4.discrepancy.settled_already");
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void aShortDeliveryIsSettledEvenBeforeTheInvoice() {
        receive("8", "0", false);
        assertThat(settle.handle(new SettleDiscrepancy(discrepancyId, "Count accepted"), seller()))
                .isNull();
        assertThat(discrepancies
                        .getDiscrepancy(discrepancyId, seller())
                        .orElseThrow()
                        .status())
                .isEqualTo(DiscrepancyView.SETTLED);
    }

    @Test
    void damagedQuantityTheInvoiceChargedIsCreditedAtTheInvoicePrice() {
        // All 10 rice arrive, 2 of them damaged: the invoice bills the 10 received.
        receive("10", "2", true);
        assertThat(discrepancies
                        .getDiscrepancy(discrepancyId, seller())
                        .orElseThrow()
                        .kind())
                .isEqualTo("DAMAGED");
        refused(() -> settle.handle(new SettleDiscrepancy(discrepancyId, "x"), buyer()), "m4.discrepancy.not_found");

        UUID creditNoteId = settle.handle(new SettleDiscrepancy(discrepancyId, "Two bags damaged"), seller());

        // 2 rice at the invoice's 120.00 = 240.00, VAT 18 % = 43.20.
        CreditNoteView note = creditNotes.getCreditNote(creditNoteId, buyer()).orElseThrow();
        assertThat(note.docNumberDisplay()).isEqualTo("D4S-CN-0000001");
        assertThat(note.discrepancyId()).isEqualTo(discrepancyId);
        assertThat(note.grossAmount()).isEqualByComparingTo("283.20");
        assertThat(note.lines()).singleElement().satisfies(line -> {
            assertThat(line.skuId()).isEqualTo(RICE);
            assertThat(line.qty()).isEqualByComparingTo("2");
            assertThat(line.unitPrice()).isEqualByComparingTo("120");
        });
        DiscrepancyView settled =
                discrepancies.getDiscrepancy(discrepancyId, buyer()).orElseThrow();
        assertThat(settled.status()).isEqualTo(DiscrepancyView.SETTLED);
        assertThat(settled.creditNoteId()).isEqualTo(creditNoteId);
        // 1200.00 + 216.00 + 320.00 = 1736.00, less 283.20.
        assertThat(invoices.balance(invoiceId, buyer()).orElseThrow().amountDue())
                .isEqualByComparingTo("1452.80");

        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .contains("DOCUMENT_ISSUED", "DISCREPANCY_SETTLED", "CREDIT_NOTE_ISSUED");
        assertThat(events(DiscrepancySettled.class)).singleElement().satisfies(event -> assertThat(event.creditNoteId())
                .isEqualTo(creditNoteId));
        assertThat(events(CreditNoteIssued.class)).singleElement().satisfies(event -> {
            assertThat(event.discrepancyId()).isEqualTo(discrepancyId);
            assertThat(event.contentHash()).hasSize(64);
        });
        assertThat(events(JournalPostingsReady.class)).singleElement().satisfies(event -> {
            assertThat(event.postings())
                    .extracting(posting -> posting.debitRole() + "/" + posting.creditRole() + "="
                            + posting.amount().toPlainString())
                    .containsExactlyInAnyOrder("REVENUE/RECEIVABLE=240.00", "VAT_OUTPUT/RECEIVABLE=43.20");
            // SettleDiscrepancy's credit note dates its postings by its own business date (CR-29-1 item 4).
            assertThat(event.businessDate())
                    .isNotNull()
                    .isEqualTo(TradingFixture.businessDateOf(superuserJdbc(), creditNoteId));
        });
    }

    @Test
    void shortAndDamagedTogetherCreditOnlyTheDamagedNeverTheShortageTwice() {
        // 8 of 10 rice arrive (2 short, never billed), 1 of the 8 damaged (billed).
        receive("8", "1", true);
        assertThat(invoices.getInvoice(invoiceId, seller()).orElseThrow().lines())
                .filteredOn(line -> line.skuId().equals(RICE))
                .singleElement()
                .satisfies(line -> assertThat(line.qty()).isEqualByComparingTo("8"));

        UUID creditNoteId = settle.handle(new SettleDiscrepancy(discrepancyId, "Short and damaged"), seller());

        assertThat(creditNotes
                        .getCreditNote(creditNoteId, seller())
                        .orElseThrow()
                        .lines())
                .singleElement()
                .satisfies(line -> assertThat(line.qty()).isEqualByComparingTo("1"));
        // 120.00 + 21.60 credited; the 2 short bags are neither billed nor credited.
        InvoiceBalance balance = invoices.balance(invoiceId, seller()).orElseThrow();
        assertThat(balance.creditedAmount()).isEqualByComparingTo("141.60");
        assertThat(balance.amountDue()).isEqualByComparingTo("1311.20");
    }

    @Test
    void damagedQuantityWaitsForTheInvoice() {
        receive("10", "2", false);
        refused(
                () -> settle.handle(new SettleDiscrepancy(discrepancyId, "Two bags damaged"), seller()),
                "m4.discrepancy.invoice_first");
        refused(
                () -> settle.handle(new SettleDiscrepancy(UUID.randomUUID(), "x"), seller()),
                "m4.discrepancy.not_found");
        refused(() -> settle.handle(new SettleDiscrepancy(discrepancyId, " "), seller()), "request.field.required");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void aCreditNoteOfChosenLinesAndItsGuards() {
        receive("8", "0", true);
        InvoiceView invoice = invoices.getInvoice(invoiceId, seller()).orElseThrow();
        UUID dhal = invoice.lines().stream()
                .filter(line -> line.skuId().equals(DHAL))
                .findFirst()
                .orElseThrow()
                .lineId();
        refused(
                () -> issueCreditNote.handle(
                        new IssueCreditNote(invoiceId, List.of(new IssueCreditNote.Line(dhal, BigDecimal.ONE)), "x"),
                        buyer()),
                "m4.creditnote.not_seller");
        refused(
                () -> issueCreditNote.handle(
                        new IssueCreditNote(
                                UUID.randomUUID(), List.of(new IssueCreditNote.Line(dhal, BigDecimal.ONE)), "x"),
                        seller()),
                "m4.invoice.not_found");
        refused(
                () -> issueCreditNote.handle(new IssueCreditNote(invoiceId, List.of(), "x"), seller()),
                "m4.creditnote.nothing_to_credit");
        refused(
                () -> issueCreditNote.handle(
                        new IssueCreditNote(
                                invoiceId, List.of(new IssueCreditNote.Line(UUID.randomUUID(), BigDecimal.ONE)), "x"),
                        seller()),
                "m4.creditnote.line_unknown");
        refused(
                () -> issueCreditNote.handle(
                        new IssueCreditNote(
                                invoiceId, List.of(new IssueCreditNote.Line(dhal, new BigDecimal("999"))), "x"),
                        seller()),
                "m4.creditnote.exceeds_billed");
        refused(
                () -> issueCreditNote.handle(
                        new IssueCreditNote(invoiceId, List.of(new IssueCreditNote.Line(dhal, BigDecimal.ZERO)), "x"),
                        seller()),
                "m4.creditnote.qty_invalid");
        assertThat(kernel.committedEvents()).isEmpty();

        // One dhal back at 80.00, EXEMPT.
        UUID creditNoteId = issueCreditNote.handle(
                new IssueCreditNote(invoiceId, List.of(new IssueCreditNote.Line(dhal, BigDecimal.ONE)), "Torn bag"),
                seller());

        assertThat(creditNotes
                        .getCreditNote(creditNoteId, seller())
                        .orElseThrow()
                        .grossAmount())
                .isEqualByComparingTo("80.00");
        assertThat(invoices.balance(invoiceId, seller()).orElseThrow().amountDue())
                .isEqualByComparingTo("1372.80");
        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .contains("CREDIT_NOTE_ISSUED");
        assertThat(events(CreditNoteIssued.class)).singleElement().satisfies(event -> assertThat(event.discrepancyId())
                .isNull());
        assertThat(events(JournalPostingsReady.class)).singleElement().satisfies(event -> assertThat(
                        event.businessDate())
                .isNotNull()
                .isEqualTo(TradingFixture.businessDateOf(superuserJdbc(), creditNoteId)));
    }

    @Test
    void theBuyerDisputesTheInvoiceAndEitherPartyResolvesIt() {
        receive("8", "0", true);
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
        resolve.handle(new ResolveInvoiceDispute(invoiceId, "Count accepted"), seller());

        assertThat(invoices.balance(invoiceId, buyer()).orElseThrow().disputed())
                .isFalse();
        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .containsExactly("INVOICE_DISPUTE_RESOLVED");
        assertThat(events(InvoiceDisputeResolved.class))
                .singleElement()
                .satisfies(event -> assertThat(event.resolvedByEntityId()).isEqualTo(SELLER));

        dispute.handle(new DisputeInvoice(invoiceId, "Still short"), buyer());
        resolve.handle(new ResolveInvoiceDispute(invoiceId, null), buyer());
        assertThat(invoices.balance(invoiceId, seller()).orElseThrow().disputed())
                .isFalse();
    }

    @Test
    void thePrintedCopyIsKeptOnTheSellersCreditNote() {
        receive("10", "2", true);
        UUID creditNoteId = settle.handle(new SettleDiscrepancy(discrepancyId, "Two bags damaged"), seller());
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
