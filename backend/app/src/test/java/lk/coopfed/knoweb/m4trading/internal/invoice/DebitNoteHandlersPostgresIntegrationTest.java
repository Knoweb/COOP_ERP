package lk.coopfed.knoweb.m4trading.internal.invoice;

import static lk.coopfed.knoweb.m4trading.TradingFixture.RICE;
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
import lk.coopfed.knoweb.m4trading.api.DebitNoteIssued;
import lk.coopfed.knoweb.m4trading.api.DisputeInvoice;
import lk.coopfed.knoweb.m4trading.api.IssueDebitNote;
import lk.coopfed.knoweb.m4trading.api.IssueInvoice;
import lk.coopfed.knoweb.m4trading.api.JournalPostingsReady;
import lk.coopfed.knoweb.m4trading.internal.grn.CaptureGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.grn.ConfirmGrnHandler;
import lk.coopfed.knoweb.m4trading.query.DeliveryQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceView;
import lk.coopfed.knoweb.m4trading.query.OrderView;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(TradingFlow.class)
class DebitNoteHandlersPostgresIntegrationTest extends PostgresIntegrationTest {

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
    DisputeInvoiceHandler disputeInvoice;

    @Autowired
    DeliveryQueries deliveries;

    @Autowired
    InvoiceQueries invoices;

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
    void successfulDebitNoteEmitsEventsAndAudit() {
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
                        List.of(new CaptureGrn.Line(
                                RICE, "EA", new BigDecimal("10"), BigDecimal.ZERO, null, null, null, null))),
                buyer());
        confirm.handle(new ConfirmGrn(grnId), buyer());
        UUID invoiceId = issueInvoice.handle(new IssueInvoice(List.of(grnId)), seller());
        InvoiceView invoice = invoices.getInvoice(invoiceId, seller()).orElseThrow();
        UUID riceInvoiceLineId = invoice.lines().stream()
                .filter(l -> l.skuId().equals(RICE))
                .findFirst()
                .orElseThrow()
                .lineId();

        kernel.reset();
        UUID debitNoteId = issueDebitNote.handle(
                new IssueDebitNote(
                        invoiceId,
                        List.of(new IssueDebitNote.Line(riceInvoiceLineId, new BigDecimal("2"))),
                        "Price correction"),
                seller());

        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .contains("DOCUMENT_ISSUED", "DOCUMENT_LINKED", "DEBIT_NOTE_ISSUED");
        assertThat(kernel.committedAudit())
                .filteredOn(record -> "DEBIT_NOTE_ISSUED".equals(record.eventType()))
                .singleElement()
                .satisfies(record -> assertThat(record.subject().id()).isEqualTo(debitNoteId));

        List<DomainEvent> events = kernel.committedEvents();
        assertThat(events).filteredOn(e -> e instanceof DebitNoteIssued).hasSize(1);
        assertThat(events).filteredOn(e -> e instanceof JournalPostingsReady).hasSize(1);
    }

    @Test
    void disputedInvoiceRefusesDebitNoteAndEmitsNoEvents() {
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
                        List.of(new CaptureGrn.Line(
                                RICE, "EA", new BigDecimal("10"), BigDecimal.ZERO, null, null, null, null))),
                buyer());
        confirm.handle(new ConfirmGrn(grnId), buyer());
        UUID invoiceId = issueInvoice.handle(new IssueInvoice(List.of(grnId)), seller());
        InvoiceView invoice = invoices.getInvoice(invoiceId, seller()).orElseThrow();
        UUID riceInvoiceLineId = invoice.lines().stream()
                .filter(l -> l.skuId().equals(RICE))
                .findFirst()
                .orElseThrow()
                .lineId();

        disputeInvoice.handle(new DisputeInvoice(invoiceId, "Wrong price"), buyer());
        kernel.reset();

        ThrowingCallable debit = () -> issueDebitNote.handle(
                new IssueDebitNote(
                        invoiceId,
                        List.of(new IssueDebitNote.Line(riceInvoiceLineId, new BigDecimal("2"))),
                        "Price correction"),
                seller());

        assertThatThrownBy(debit).isInstanceOf(ProblemException.class).hasMessage("m4.debitnote.invoice_disputed");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }
}
