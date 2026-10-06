package lk.coopfed.knoweb.m4trading.internal.payment;

import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.DHAL;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RELATIONSHIP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SHOP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.STRANGER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static lk.coopfed.knoweb.m4trading.TradingFixture.seller;
import static lk.coopfed.knoweb.m4trading.TradingFixture.today;
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
import lk.coopfed.knoweb.m4trading.api.ChequeBounced;
import lk.coopfed.knoweb.m4trading.api.ChequeCleared;
import lk.coopfed.knoweb.m4trading.api.ConfirmGrn;
import lk.coopfed.knoweb.m4trading.api.ExposureWarning;
import lk.coopfed.knoweb.m4trading.api.IssueCreditNote;
import lk.coopfed.knoweb.m4trading.api.IssueInvoice;
import lk.coopfed.knoweb.m4trading.api.JournalPostingsReady;
import lk.coopfed.knoweb.m4trading.api.OrderAccepted;
import lk.coopfed.knoweb.m4trading.api.PaymentReceiptRecorded;
import lk.coopfed.knoweb.m4trading.api.PaymentReceiptReversed;
import lk.coopfed.knoweb.m4trading.api.RecordChequeOutcome;
import lk.coopfed.knoweb.m4trading.api.RecordPaymentReceipt;
import lk.coopfed.knoweb.m4trading.internal.grn.CaptureGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.grn.ConfirmGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.invoice.IssueCreditNoteHandler;
import lk.coopfed.knoweb.m4trading.internal.invoice.IssueInvoiceHandler;
import lk.coopfed.knoweb.m4trading.query.CreditNoteQueries;
import lk.coopfed.knoweb.m4trading.query.DeliveryQueries;
import lk.coopfed.knoweb.m4trading.query.ExposureQueries;
import lk.coopfed.knoweb.m4trading.query.ExposureView;
import lk.coopfed.knoweb.m4trading.query.InvoiceBalance;
import lk.coopfed.knoweb.m4trading.query.InvoiceQueries;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.query.PaymentQueries;
import lk.coopfed.knoweb.m4trading.query.PaymentReceiptView;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * M4-07 and M4-09 (24A section 6.3): a payment settles invoices (chosen, or oldest first) and holds
 * what is left on account; a bounced cheque reverses the receipt and reopens the invoice, which can
 * then be paid again; a credit note of a paid invoice holds its money unapplied (CR-24A-3 item 2);
 * the exposure sums open invoices
 * and accepted orders less what is on account, and an acceptance across a threshold of the credit
 * limit warns without refusing. Every guard, and what each audits and publishes.
 */
@Import(TradingFlow.class)
class PaymentHandlersPostgresIntegrationTest extends PostgresIntegrationTest {

    /** 10 rice at 120.00 + 18 % and 4 dhal at 80.00 exempt: 1200.00 + 216.00 + 320.00. */
    private static final BigDecimal INVOICE_GROSS = new BigDecimal("1736.00");

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
    RecordPaymentReceiptHandler record;

    @Autowired
    RecordChequeOutcomeHandler outcome;

    @Autowired
    InvoiceQueries invoices;

    @Autowired
    CreditNoteQueries creditNotes;

    @Autowired
    PaymentQueries payments;

    @Autowired
    ExposureQueries exposures;

    @Autowired
    DeliveryQueries deliveries;

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    /** An order delivered in full, received in full and invoiced: 1736.00. */
    private UUID invoiced() {
        UUID noteId = flow.dispatchedNote(SHOP);
        UUID dropId = deliveries
                .getDeliveryNote(noteId, buyer())
                .orElseThrow()
                .drops()
                .get(0)
                .dropId();
        UUID grnId =
                capture.handle(new CaptureGrn(dropId, SHOP, null, List.of(line(RICE, "10"), line(DHAL, "4"))), buyer());
        confirm.handle(new ConfirmGrn(grnId), buyer());
        return issueInvoice.handle(new IssueInvoice(List.of(grnId)), seller());
    }

    @Test
    void aPaymentSettlesTheOldestInvoicesFirstAndHoldsTheRestOnAccount() {
        UUID first = invoiced();
        UUID second = invoiced();
        assertThat(invoices.balance(first, seller()).orElseThrow().paymentState())
                .isEqualTo(InvoiceBalance.OPEN);
        kernel.reset();

        UUID receiptId = record.handle(payment("TRANSFER", "2000.00", null, List.of()), seller());

        InvoiceBalance paid = invoices.balance(first, buyer()).orElseThrow();
        assertThat(paid.settledAmount()).isEqualByComparingTo("1736.00");
        assertThat(paid.amountDue()).isEqualByComparingTo("0");
        assertThat(paid.paymentState()).isEqualTo(InvoiceBalance.SETTLED);
        InvoiceBalance part = invoices.balance(second, buyer()).orElseThrow();
        assertThat(part.settledAmount()).isEqualByComparingTo("264.00");
        assertThat(part.amountDue()).isEqualByComparingTo("1472.00");
        assertThat(part.paymentState()).isEqualTo(InvoiceBalance.PART_PAID);

        PaymentReceiptView receipt = payments.getReceipt(receiptId, buyer()).orElseThrow();
        assertThat(receipt.docNumberDisplay()).isEqualTo("D4S-PRC-0000001");
        assertThat(receipt.status()).isEqualTo(PaymentReceiptView.RECORDED);
        assertThat(receipt.unappliedAmount()).isEqualByComparingTo("0");
        assertThat(receipt.allocations())
                .extracting(PaymentReceiptView.AllocationView::invoiceId)
                .containsExactly(first, second);
        assertThat(payments.listReceipts(OrderQueries.Role.BUYER, buyer()))
                .extracting(PaymentReceiptView::receiptId)
                .containsExactly(receiptId);
        assertThat(payments.receiptsOf(second, buyer()))
                .extracting(PaymentReceiptView::receiptId)
                .containsExactly(receiptId);

        assertThat(kernel.committedAudit())
                .extracting(audit -> audit.eventType())
                .contains("DOCUMENT_ISSUED", "PAYMENT_RECEIPT_RECORDED");
        assertThat(events(PaymentReceiptRecorded.class)).singleElement().satisfies(event -> {
            assertThat(event.amount()).isEqualByComparingTo("2000.00");
            assertThat(event.buyerEntityId()).isEqualTo(BUYER);
            assertThat(event.settlements()).hasSize(2);
            assertThat(event.unappliedAmount()).isEqualByComparingTo("0");
        });
        assertThat(events(JournalPostingsReady.class)).singleElement().satisfies(event -> {
            assertThat(event.docTypeCode()).isEqualTo("PRC");
            assertThat(event.postings()).isNotEmpty();
        });

        // An overpayment is never refused: what no open invoice takes stays on the buyer's account.
        UUID overpaid = record.handle(payment("CASH", "2000.00", null, List.of()), seller());
        assertThat(invoices.balance(second, seller()).orElseThrow().paymentState())
                .isEqualTo(InvoiceBalance.SETTLED);
        assertThat(payments.getReceipt(overpaid, seller()).orElseThrow().unappliedAmount())
                .isEqualByComparingTo("528.00");
        ExposureView exposure = exposures.exposure(SELLER, BUYER, seller()).orElseThrow();
        assertThat(exposure.openInvoices()).isEqualByComparingTo("0");
        assertThat(exposure.unappliedReceipts()).isEqualByComparingTo("528.00");
        assertThat(exposure.amount()).isEqualByComparingTo("-528.00");
    }

    @Test
    void chosenSettlementsAreCheckedAgainstEachInvoice() {
        UUID invoiceId = invoiced();
        kernel.reset();

        refused(
                () -> record.handle(payment("TRANSFER", "2000.00", null, settle(invoiceId, "1736.01")), seller()),
                "m4.payment.exceeds_due");
        refused(
                () -> record.handle(payment("TRANSFER", "100.00", null, settle(invoiceId, "100.01")), seller()),
                "m4.payment.exceeds_receipt");
        refused(
                () -> record.handle(
                        payment(
                                "TRANSFER",
                                "100.00",
                                null,
                                List.of(
                                        new RecordPaymentReceipt.Settlement(invoiceId, new BigDecimal("10")),
                                        new RecordPaymentReceipt.Settlement(invoiceId, new BigDecimal("10")))),
                        seller()),
                "m4.payment.invoice_invalid");
        refused(
                () -> record.handle(payment("CHEQUE", "100.00", null, List.of()), seller()),
                "m4.payment.cheque_required");
        refused(
                () -> record.handle(payment("BARTER", "100.00", null, List.of()), seller()),
                "m4.payment.method_invalid");
        refused(() -> record.handle(payment("CASH", "0", null, List.of()), seller()), "m4.payment.amount_invalid");
        refused(
                () -> record.handle(
                        new RecordPaymentReceipt(
                                BUYER, "CASH", new BigDecimal("10"), null, today().plusDays(1), null, List.of()),
                        seller()),
                "m4.payment.received_in_future");
        refused(
                () -> record.handle(
                        new RecordPaymentReceipt(STRANGER, "CASH", new BigDecimal("10"), null, null, null, List.of()),
                        seller()),
                "m4.payment.no_relationship");
        // The buyer cannot record its own payment: it sells nothing to the seller.
        refused(
                () -> record.handle(
                        new RecordPaymentReceipt(SELLER, "CASH", new BigDecimal("10"), null, null, null, List.of()),
                        buyer()),
                "m4.payment.no_relationship");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        record.handle(payment("TRANSFER", "500.00", null, settle(invoiceId, "400.00")), seller());
        InvoiceBalance balance = invoices.balance(invoiceId, seller()).orElseThrow();
        assertThat(balance.settledAmount()).isEqualByComparingTo("400.00");
        assertThat(balance.paymentState()).isEqualTo(InvoiceBalance.PART_PAID);
        assertThat(exposures.exposure(SELLER, BUYER, buyer()).orElseThrow().unappliedReceipts())
                .isEqualByComparingTo("100.00");
    }

    @Test
    void aBouncedChequeReversesTheReceiptAndReopensTheInvoice() {
        UUID invoiceId = invoiced();
        UUID receiptId = record.handle(payment("CHEQUE", "1736.00", cheque(), List.of()), seller());
        assertThat(invoices.balance(invoiceId, seller()).orElseThrow().paymentState())
                .isEqualTo(InvoiceBalance.SETTLED);
        kernel.reset();

        refused(
                () -> outcome.handle(new RecordChequeOutcome(receiptId, "BOUNCED", null), buyer()),
                "m4.payment.not_seller");
        refused(
                () -> outcome.handle(new RecordChequeOutcome(receiptId, "LOST", null), seller()),
                "m4.payment.outcome_invalid");
        refused(
                () -> outcome.handle(new RecordChequeOutcome(UUID.randomUUID(), "BOUNCED", null), seller()),
                "m4.payment.not_found");

        UUID reversalId = outcome.handle(new RecordChequeOutcome(receiptId, "BOUNCED", "Refer to drawer"), seller());

        InvoiceBalance reopened = invoices.balance(invoiceId, buyer()).orElseThrow();
        assertThat(reopened.settledAmount()).isEqualByComparingTo("0");
        assertThat(reopened.amountDue()).isEqualByComparingTo(INVOICE_GROSS);
        assertThat(reopened.paymentState()).isEqualTo(InvoiceBalance.OPEN);
        PaymentReceiptView bounced = payments.getReceipt(receiptId, buyer()).orElseThrow();
        assertThat(bounced.status()).isEqualTo(PaymentReceiptView.REVERSED);
        assertThat(bounced.reversedBy()).isEqualTo(reversalId);
        assertThat(bounced.cheque().outcome()).isEqualTo("BOUNCED");
        PaymentReceiptView reversal = payments.getReceipt(reversalId, buyer()).orElseThrow();
        assertThat(reversal.status()).isEqualTo(PaymentReceiptView.REVERSAL);
        assertThat(reversal.reversalOf()).isEqualTo(receiptId);
        assertThat(reversal.allocations()).singleElement().satisfies(allocation -> assertThat(allocation.amount())
                .isEqualByComparingTo("-1736.00"));

        assertThat(kernel.committedAudit())
                .extracting(audit -> audit.eventType())
                .contains("DOCUMENT_ISSUED", "CHEQUE_BOUNCED");
        assertThat(events(ChequeBounced.class)).singleElement().satisfies(event -> {
            assertThat(event.reversalId()).isEqualTo(reversalId);
            assertThat(event.reason()).isEqualTo("Refer to drawer");
        });
        assertThat(events(PaymentReceiptReversed.class)).singleElement().satisfies(event -> assertThat(event.reopened())
                .singleElement()
                .satisfies(settlement -> assertThat(settlement.invoiceId()).isEqualTo(invoiceId)));
        assertThat(events(JournalPostingsReady.class)).singleElement().satisfies(event -> assertThat(event.documentId())
                .isEqualTo(reversalId));

        kernel.reset();
        refused(
                () -> outcome.handle(new RecordChequeOutcome(receiptId, "CLEARED", null), seller()),
                "m4.payment.outcome_recorded");
        assertThat(kernel.committedEvents()).isEmpty();

        // The invoice is open again, so the buyer pays it again, and it settles.
        record.handle(payment("TRANSFER", "1736.00", null, settle(invoiceId, "1736.00")), seller());
        assertThat(invoices.balance(invoiceId, seller()).orElseThrow().paymentState())
                .isEqualTo(InvoiceBalance.SETTLED);
    }

    @Test
    void aClearedChequeReversesNothing() {
        UUID invoiceId = invoiced();
        UUID transfer = record.handle(payment("TRANSFER", "10.00", null, List.of()), seller());
        refused(
                () -> outcome.handle(new RecordChequeOutcome(transfer, "CLEARED", null), seller()),
                "m4.payment.not_cheque");
        UUID receiptId = record.handle(payment("CHEQUE", "100.00", cheque(), List.of()), seller());
        kernel.reset();

        assertThat(outcome.handle(new RecordChequeOutcome(receiptId, "CLEARED", null), seller()))
                .isNull();

        assertThat(payments.getReceipt(receiptId, buyer())
                        .orElseThrow()
                        .cheque()
                        .outcome())
                .isEqualTo("CLEARED");
        assertThat(invoices.balance(invoiceId, seller()).orElseThrow().settledAmount())
                .isEqualByComparingTo("110.00");
        assertThat(kernel.committedAudit())
                .extracting(audit -> audit.eventType())
                .containsExactly("CHEQUE_CLEARED");
        assertThat(events(ChequeCleared.class)).singleElement().satisfies(event -> assertThat(event.amount())
                .isEqualByComparingTo("100.00"));
        assertThat(events(PaymentReceiptReversed.class)).isEmpty();
    }

    @Test
    void aCreditNoteOfAPaidInvoiceHoldsItsMoneyUnapplied() {
        // CR-24A-3 item 2 (B-1): the credit note is issued for the goods in full; the invoice owes
        // nothing, so none of its money applies there and the whole of it is unapplied, lowering
        // the exposure until ApplyCreditNote applies it to another invoice.
        UUID invoiceId = invoiced();
        record.handle(payment("TRANSFER", "1736.00", null, List.of()), seller());
        UUID riceLine = invoices.getInvoice(invoiceId, seller()).orElseThrow().lines().stream()
                .filter(line -> RICE.equals(line.skuId()))
                .findFirst()
                .orElseThrow()
                .lineId();
        kernel.reset();

        UUID creditNoteId = issueCreditNote.handle(
                new IssueCreditNote(invoiceId, List.of(new IssueCreditNote.Line(riceLine, BigDecimal.ONE)), "damaged"),
                seller());

        assertThat(creditNotes.getCreditNote(creditNoteId, buyer()).orElseThrow())
                .satisfies(note -> {
                    assertThat(note.grossAmount()).isEqualByComparingTo("141.60");
                    assertThat(note.appliedAmount()).isEqualByComparingTo("0");
                    assertThat(note.unappliedAmount()).isEqualByComparingTo("141.60");
                });
        InvoiceBalance balance = invoices.balance(invoiceId, seller()).orElseThrow();
        assertThat(balance.creditedAmount()).isEqualByComparingTo("0");
        assertThat(balance.amountDue()).isEqualByComparingTo("0");
        assertThat(exposures.exposure(SELLER, BUYER, seller()).orElseThrow().unappliedCredits())
                .isEqualByComparingTo("141.60");
        assertThat(kernel.committedAudit())
                .extracting(audit -> audit.eventType())
                .contains("CREDIT_NOTE_ISSUED");
        assertThat(kernel.committedEvents()).hasAtLeastOneElementOfType(JournalPostingsReady.class);
    }

    @Test
    void anAcceptanceAcrossTheCreditLimitWarnsAndIsNotRefused() {
        // 10 rice at 120.00 and 4 dhal at 80.00: 1520.00 accepted, not invoiced (net, as 24A's formula).
        superuserJdbc()
                .update(
                        "update party.entity_relationship set credit_limit = 1800.00 where relationship_id = ?",
                        RELATIONSHIP);
        kernel.reset();

        flow.acceptedOrder();

        ExposureView exposure = exposures.exposure(SELLER, BUYER, seller()).orElseThrow();
        assertThat(exposure.acceptedNotInvoiced()).isEqualByComparingTo("1520.00");
        assertThat(exposure.amount()).isEqualByComparingTo("1520.00");
        assertThat(exposure.creditLimit()).isEqualByComparingTo("1800.00");
        assertThat(exposure.warnThresholdPercent()).isEqualTo(80);
        assertThat(events(OrderAccepted.class)).hasSize(1);
        assertThat(events(ExposureWarning.class)).singleElement().satisfies(warning -> {
            assertThat(warning.thresholdPercent()).isEqualTo(80);
            assertThat(warning.amount()).isEqualByComparingTo("1520.00");
            assertThat(warning.creditLimit()).isEqualByComparingTo("1800.00");
        });
        // The buyer reads the same exposure of itself.
        assertThat(exposures.listExposures(OrderQueries.Role.BUYER, buyer()))
                .singleElement()
                .satisfies(own -> assertThat(own.amount()).isEqualByComparingTo("1520.00"));

        // A second order takes it over the limit: accepted all the same (ADR-12), warned at 100 %.
        kernel.reset();
        flow.acceptedOrder();
        assertThat(exposures.exposure(SELLER, BUYER, seller()).orElseThrow().amount())
                .isEqualByComparingTo("3040.00");
        assertThat(events(ExposureWarning.class))
                .singleElement()
                .satisfies(warning -> assertThat(warning.thresholdPercent()).isEqualTo(100));

        // Invoiced, an order leaves the accepted-not-invoiced part and joins the open invoices.
        kernel.reset();
        invoiced();
        ExposureView after = exposures.exposure(SELLER, BUYER, seller()).orElseThrow();
        assertThat(after.openInvoices()).isEqualByComparingTo(INVOICE_GROSS);
        assertThat(after.acceptedNotInvoiced()).isEqualByComparingTo("3040.00");
        assertThat(events(ExposureWarning.class)).isEmpty();
    }

    private static RecordPaymentReceipt payment(
            String method,
            String amount,
            RecordPaymentReceipt.Cheque cheque,
            List<RecordPaymentReceipt.Settlement> settlements) {
        return new RecordPaymentReceipt(BUYER, method, new BigDecimal(amount), "REF-1", today(), cheque, settlements);
    }

    private static List<RecordPaymentReceipt.Settlement> settle(UUID invoiceId, String amount) {
        return List.of(new RecordPaymentReceipt.Settlement(invoiceId, new BigDecimal(amount)));
    }

    private static RecordPaymentReceipt.Cheque cheque() {
        return new RecordPaymentReceipt.Cheque("Bank of Ceylon", "400123", today());
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
