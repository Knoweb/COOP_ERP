package lk.coopfed.knoweb.m4trading.internal.invoice;

import static lk.coopfed.knoweb.m4trading.TradingFixture.BUYER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SELLER;
import static lk.coopfed.knoweb.m4trading.TradingFixture.SHOP;
import static lk.coopfed.knoweb.m4trading.TradingFixture.buyer;
import static lk.coopfed.knoweb.m4trading.TradingFixture.seller;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.m4trading.TradingFixture;
import lk.coopfed.knoweb.m4trading.TradingFlow;
import lk.coopfed.knoweb.m4trading.api.CaptureGrn;
import lk.coopfed.knoweb.m4trading.api.ConfirmGrn;
import lk.coopfed.knoweb.m4trading.api.IssueCreditNote;
import lk.coopfed.knoweb.m4trading.api.IssueDebitNote;
import lk.coopfed.knoweb.m4trading.api.IssueInvoice;
import lk.coopfed.knoweb.m4trading.api.RecordPaymentReceipt;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.grn.CaptureGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.grn.ConfirmGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.payment.RecordPaymentReceiptHandler;
import lk.coopfed.knoweb.m4trading.query.DeliveryQueries;
import lk.coopfed.knoweb.m4trading.query.ExposureQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceBalance;
import lk.coopfed.knoweb.m4trading.query.InvoiceQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceView;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.query.OrderView;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(TradingFlow.class)
class CrossFeatureBalancePostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    TradingFlow flow;

    @Autowired
    CaptureGrnHandler capture;

    @Autowired
    ConfirmGrnHandler confirm;

    @Autowired
    lk.coopfed.knoweb.m4trading.internal.delivery.IssueDeliveryNoteHandler issueNote;

    @Autowired
    lk.coopfed.knoweb.m4trading.internal.delivery.DispatchDeliveryNoteHandler dispatchNote;

    @Autowired
    IssueInvoiceHandler issueInvoice;

    @Autowired
    IssueDebitNoteHandler issueDebitNote;

    @Autowired
    lk.coopfed.knoweb.m4trading.internal.invoice.IssueCreditNoteHandler issueCreditNote;

    @Autowired
    RecordPaymentReceiptHandler recordPayment;

    @Autowired
    OrderQueries orders;

    @Autowired
    DeliveryQueries deliveries;

    @Autowired
    InvoiceQueries invoices;

    @Autowired
    ExposureQueries exposureQueries;

    @Autowired
    TradingClock clock;

    @BeforeEach
    void arrange() {
        TradingFixture.arrange(superuserJdbc());
        kernel.reset();
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    @Test
    void crossFeatureBalanceIsMathematicallyCorrect() {
        OrderView order = flow.acceptedOrder();

        UUID noteId = flow.draftNote(order, SHOP);
        issueNote.handle(new lk.coopfed.knoweb.m4trading.api.IssueDeliveryNote(noteId), seller());

        dispatchNote.handle(
                new lk.coopfed.knoweb.m4trading.api.DispatchDeliveryNote(noteId, null, null, null), seller());

        UUID dropId = deliveries
                .getDeliveryNote(noteId, buyer())
                .orElseThrow()
                .drops()
                .get(0)
                .dropId();

        UUID grnId = capture.handle(
                new CaptureGrn(
                        dropId,
                        SHOP,
                        null,
                        List.of(
                                new CaptureGrn.Line(
                                        RICE, "EA", new BigDecimal("8"), BigDecimal.ZERO, null, null, null, null),
                                new CaptureGrn.Line(
                                        TradingFixture.DHAL,
                                        "EA",
                                        new BigDecimal("4"),
                                        BigDecimal.ZERO,
                                        null,
                                        null,
                                        null,
                                        null))),
                buyer());
        confirm.handle(new ConfirmGrn(grnId), buyer());

        UUID invoiceId = issueInvoice.handle(new IssueInvoice(List.of(grnId)), seller());
        InvoiceView invoice = invoices.getInvoice(invoiceId, seller()).orElseThrow();
        InvoiceBalance balance = invoices.balance(invoiceId, seller()).orElseThrow();

        assertThat(invoice.grossAmount()).isEqualByComparingTo("1452.80");
        assertThat(balance.amountDue()).isEqualByComparingTo("1452.80");

        assertThat(exposureQueries
                        .exposure(SELLER, BUYER, seller())
                        .orElseThrow()
                        .amount())
                .isEqualByComparingTo("1452.80");

        UUID riceInvoiceLineId = invoice.lines().stream()
                .filter(l -> l.skuId().equals(RICE))
                .findFirst()
                .orElseThrow()
                .lineId();

        issueDebitNote.handle(
                new IssueDebitNote(
                        invoiceId,
                        List.of(new IssueDebitNote.Line(riceInvoiceLineId, new BigDecimal("2"))),
                        "Price correction"),
                seller());

        InvoiceBalance afterDebit = invoices.balance(invoiceId, seller()).orElseThrow();
        assertThat(afterDebit.debitedAmount()).isEqualByComparingTo("283.20");
        assertThat(afterDebit.amountDue()).isEqualByComparingTo("1736.00");

        assertThat(exposureQueries
                        .exposure(SELLER, BUYER, seller())
                        .orElseThrow()
                        .amount())
                .isEqualByComparingTo("1736.00");

        issueCreditNote.handle(
                new IssueCreditNote(
                        invoiceId,
                        List.of(new IssueCreditNote.Line(riceInvoiceLineId, new BigDecimal("1"))),
                        "Damaged"),
                seller());

        InvoiceBalance afterCredit = invoices.balance(invoiceId, seller()).orElseThrow();
        assertThat(afterCredit.creditedAmount()).isEqualByComparingTo("141.60");
        assertThat(afterCredit.amountDue()).isEqualByComparingTo("1594.40");

        assertThat(exposureQueries
                        .exposure(SELLER, BUYER, seller())
                        .orElseThrow()
                        .amount())
                .isEqualByComparingTo("1594.40");

        recordPayment.handle(
                new RecordPaymentReceipt(
                        BUYER,
                        "CASH",
                        new BigDecimal("1594.40"),
                        "Receipt-001",
                        clock.today(),
                        null,
                        List.of(new RecordPaymentReceipt.Settlement(invoiceId, new BigDecimal("1594.40")))),
                seller());

        InvoiceBalance finalInvoice = invoices.balance(invoiceId, seller()).orElseThrow();
        assertThat(finalInvoice.settledAmount()).isEqualByComparingTo("1594.40");
        assertThat(finalInvoice.amountDue()).isEqualByComparingTo("0.00");

        lk.coopfed.knoweb.m4trading.query.ExposureView exposure =
                exposureQueries.exposure(SELLER, BUYER, seller()).orElseThrow();
        assertThat(exposure.amount()).isEqualByComparingTo("0.00");
    }
}
