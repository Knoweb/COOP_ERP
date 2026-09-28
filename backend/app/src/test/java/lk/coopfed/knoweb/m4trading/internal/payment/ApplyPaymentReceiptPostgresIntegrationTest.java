package lk.coopfed.knoweb.m4trading.internal.payment;

import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.DHAL;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SHOP;
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
import lk.coopfed.knoweb.m4trading.api.ApplyPaymentReceipt;
import lk.coopfed.knoweb.m4trading.api.CaptureGrn;
import lk.coopfed.knoweb.m4trading.api.ConfirmGrn;
import lk.coopfed.knoweb.m4trading.api.IssueInvoice;
import lk.coopfed.knoweb.m4trading.api.PaymentReceiptApplied;
import lk.coopfed.knoweb.m4trading.api.PaymentReceiptReversed;
import lk.coopfed.knoweb.m4trading.api.RecordChequeOutcome;
import lk.coopfed.knoweb.m4trading.api.RecordPaymentReceipt;
import lk.coopfed.knoweb.m4trading.internal.grn.CaptureGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.grn.ConfirmGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.invoice.IssueInvoiceHandler;
import lk.coopfed.knoweb.m4trading.query.DeliveryQueries;
import lk.coopfed.knoweb.m4trading.query.ExposureQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceBalance;
import lk.coopfed.knoweb.m4trading.query.InvoiceQueries;
import lk.coopfed.knoweb.m4trading.query.PaymentQueries;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * ApplyReceipt (24A section 6.3): money a receipt left on the buyer's account settles an invoice
 * issued later; every guard, what is audited and published; and a bounced cheque takes the
 * application back with the rest of the receipt.
 */
@Import(TradingFlow.class)
class ApplyPaymentReceiptPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    TradingFlow flow;

    @Autowired
    CaptureGrnHandler capture;

    @Autowired
    ConfirmGrnHandler confirm;

    @Autowired
    IssueInvoiceHandler issueInvoice;

    @Autowired
    RecordPaymentReceiptHandler record;

    @Autowired
    ApplyPaymentReceiptHandler apply;

    @Autowired
    RecordChequeOutcomeHandler outcome;

    @Autowired
    InvoiceQueries invoices;

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

    /** An order delivered, received and invoiced in full: 1736.00. */
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

    /** 2000.00 against the one open invoice of 1736.00: 264.00 left on account. */
    private UUID overpaid(String method, RecordPaymentReceipt.Cheque cheque) {
        return record.handle(
                new RecordPaymentReceipt(BUYER, method, new BigDecimal("2000.00"), "REF-1", today(), cheque, List.of()),
                seller());
    }

    @Test
    void moneyOnAccountSettlesALaterInvoiceOldestFirst() {
        UUID first = invoiced();
        UUID receiptId = overpaid("TRANSFER", null);
        UUID later = invoiced();
        assertThat(exposures.exposure(SELLER, BUYER, seller()).orElseThrow().unappliedReceipts())
                .isEqualByComparingTo("264.00");
        kernel.reset();

        UUID applicationId = apply.handle(new ApplyPaymentReceipt(receiptId, List.of()), seller());

        InvoiceBalance part = invoices.balance(later, buyer()).orElseThrow();
        assertThat(part.settledAmount()).isEqualByComparingTo("264.00");
        assertThat(part.paymentState()).isEqualTo(InvoiceBalance.PART_PAID);
        assertThat(invoices.balance(first, buyer()).orElseThrow().paymentState())
                .isEqualTo(InvoiceBalance.SETTLED);
        assertThat(payments.getReceipt(receiptId, buyer()).orElseThrow().unappliedAmount())
                .isEqualByComparingTo("0");
        assertThat(payments.receiptsOf(later, seller())).singleElement().satisfies(r -> assertThat(r.receiptId())
                .isEqualTo(receiptId));
        assertThat(exposures.exposure(SELLER, BUYER, seller()).orElseThrow().unappliedReceipts())
                .isEqualByComparingTo("0");

        assertThat(kernel.committedAudit())
                .extracting(audit -> audit.eventType())
                .containsExactly("PAYMENT_RECEIPT_APPLIED");
        assertThat(events(PaymentReceiptApplied.class)).singleElement().satisfies(event -> {
            assertThat(event.applicationId()).isEqualTo(applicationId);
            assertThat(event.receiptId()).isEqualTo(receiptId);
            assertThat(event.appliedAmount()).isEqualByComparingTo("264.00");
            assertThat(event.unappliedAmount()).isEqualByComparingTo("0");
            assertThat(event.settlements()).singleElement().satisfies(s -> assertThat(s.invoiceId())
                    .isEqualTo(later));
        });

        kernel.reset();
        refused(
                () -> apply.handle(new ApplyPaymentReceipt(receiptId, List.of()), seller()),
                "m4.payment.nothing_on_account");
        assertThat(kernel.committedAudit()).isEmpty();
    }

    @Test
    void everyBrokenGuardIsRefusedAndNothingIsCommitted() {
        invoiced();
        UUID receiptId = overpaid("CASH", null);
        kernel.reset();

        refused(
                () -> apply.handle(new ApplyPaymentReceipt(UUID.randomUUID(), List.of()), seller()),
                "m4.payment.not_found");
        refused(() -> apply.handle(new ApplyPaymentReceipt(receiptId, List.of()), buyer()), "m4.payment.not_seller");
        // No invoice is open yet: the money has nowhere to go.
        refused(
                () -> apply.handle(new ApplyPaymentReceipt(receiptId, List.of()), seller()),
                "m4.payment.nothing_to_apply");
        UUID later = invoiced();
        kernel.reset();
        refused(
                () -> apply.handle(
                        new ApplyPaymentReceipt(
                                receiptId,
                                List.of(new RecordPaymentReceipt.Settlement(later, new BigDecimal("264.01")))),
                        seller()),
                "m4.payment.exceeds_on_account");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void aBouncedChequeTakesTheApplicationBackWithTheReceipt() {
        UUID first = invoiced();
        UUID receiptId = overpaid("CHEQUE", new RecordPaymentReceipt.Cheque("Bank of Ceylon", "400123", today()));
        UUID later = invoiced();
        apply.handle(new ApplyPaymentReceipt(receiptId, List.of()), seller());
        kernel.reset();

        outcome.handle(new RecordChequeOutcome(receiptId, "BOUNCED", "Refer to drawer"), seller());

        assertThat(invoices.balance(first, buyer()).orElseThrow().paymentState())
                .isEqualTo(InvoiceBalance.OPEN);
        assertThat(invoices.balance(later, buyer()).orElseThrow().paymentState())
                .isEqualTo(InvoiceBalance.OPEN);
        assertThat(events(PaymentReceiptReversed.class)).singleElement().satisfies(event -> assertThat(event.reopened())
                .hasSize(2));
        refused(() -> apply.handle(new ApplyPaymentReceipt(receiptId, List.of()), seller()), "m4.payment.not_recorded");
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
