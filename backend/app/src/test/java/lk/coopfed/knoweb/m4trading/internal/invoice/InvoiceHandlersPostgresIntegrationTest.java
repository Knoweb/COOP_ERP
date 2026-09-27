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
import lk.coopfed.knoweb.m4trading.api.InvoiceIssued;
import lk.coopfed.knoweb.m4trading.api.InvoicePrinted;
import lk.coopfed.knoweb.m4trading.api.IssueInvoice;
import lk.coopfed.knoweb.m4trading.api.JournalPostingsReady;
import lk.coopfed.knoweb.m4trading.api.RecordInvoicePrint;
import lk.coopfed.knoweb.m4trading.internal.grn.CaptureGrnHandler;
import lk.coopfed.knoweb.m4trading.internal.grn.ConfirmGrnHandler;
import lk.coopfed.knoweb.m4trading.query.DeliveryQueries;
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

/** IssueInvoice (24A section 6): the invoice from a confirmed GRN at the priced lines, VAT, postings, guards. */
@Import(TradingFlow.class)
class InvoiceHandlersPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    TradingFlow flow;

    @Autowired
    CaptureGrnHandler capture;

    @Autowired
    ConfirmGrnHandler confirm;

    @Autowired
    IssueInvoiceHandler issue;

    @Autowired
    RecordInvoicePrintHandler print;

    @Autowired
    InvoiceQueries invoices;

    @Autowired
    DeliveryQueries deliveries;

    private UUID grnId;

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
        // 8 rice of 10 arrive (short by 2), the 4 dhal in full.
        grnId = capture.handle(new CaptureGrn(dropId, SHOP, null, List.of(line(RICE, "8"), line(DHAL, "4"))), buyer());
        kernel.reset();
    }

    @AfterEach
    void clean() {
        TradingFixture.clean(superuserJdbc());
    }

    @Test
    void theSellerInvoicesTheConfirmedGrnAtTheReceivedQuantitiesWithVat() {
        refused(() -> issue.handle(new IssueInvoice(List.of(grnId)), seller()), "m4.invoice.grn_not_confirmed");
        confirm.handle(new ConfirmGrn(grnId), buyer());
        kernel.reset();

        UUID invoiceId = issue.handle(new IssueInvoice(List.of(grnId)), seller());

        InvoiceView invoice = invoices.getInvoice(invoiceId, buyer()).orElseThrow();
        assertThat(invoice.docNumberDisplay()).isEqualTo("D4S-INV-0000001");
        // 8 x 120 + 4 x 80 = 1280.00; VAT at the register's 18 % (m4.demo.vat_rate_percent).
        assertThat(invoice.netAmount()).isEqualByComparingTo("1280.00");
        assertThat(invoice.taxAmount()).isEqualByComparingTo("230.40");
        assertThat(invoice.grossAmount()).isEqualByComparingTo("1510.40");
        assertThat(invoice.sellerVatNo()).isEqualTo("209876543-7000");
        // CR-21A-6: the buyer's VAT number, read through M1's counterparty view now that it
        // carries it (an active relationship between SELLER and BUYER; TradingFixture.arrange).
        assertThat(invoice.buyerVatNo()).isEqualTo("109876543-7000");
        assertThat(invoice.grnIds()).containsExactly(grnId);
        assertThat(invoice.lines()).hasSize(2).allSatisfy(line -> assertThat(line.batchId())
                .isNotNull());
        assertThat(invoices.listInvoices(OrderQueries.Role.BUYER, buyer())).hasSize(1);

        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .contains("DOCUMENT_ISSUED", "INVOICE_ISSUED");
        assertThat(events(InvoiceIssued.class)).singleElement().satisfies(event -> {
            assertThat(event.buyerEntityId()).isEqualTo(BUYER);
            assertThat(event.buyerVatNo()).isEqualTo("109876543-7000");
            assertThat(event.grossAmount()).isEqualByComparingTo("1510.40");
            assertThat(event.contentHash()).hasSize(64);
        });
        assertThat(events(JournalPostingsReady.class)).singleElement().satisfies(event -> {
            assertThat(event.ownerEntityId()).isEqualTo(SELLER);
            assertThat(event.postings())
                    .extracting(posting ->
                            posting.creditRole() + "=" + posting.amount().toPlainString())
                    .containsExactlyInAnyOrder("REVENUE=1280.00", "VAT_OUTPUT=230.40");
        });

        kernel.reset();
        refused(() -> issue.handle(new IssueInvoice(List.of(grnId)), seller()), "m4.invoice.grn_invoiced");
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void theGuardsOfIssueInvoice() {
        confirm.handle(new ConfirmGrn(grnId), buyer());
        kernel.reset();
        refused(() -> issue.handle(new IssueInvoice(List.of()), seller()), "m4.invoice.grns_required");
        refused(() -> issue.handle(new IssueInvoice(List.of(UUID.randomUUID())), seller()), "m4.invoice.grn_unknown");
        refused(() -> issue.handle(new IssueInvoice(List.of(grnId)), buyer()), "m4.invoice.grn_unknown");
        superuserJdbc().update("update party.entity set vat_registration_no = null where entity_id = ?", SELLER);
        refused(() -> issue.handle(new IssueInvoice(List.of(grnId)), seller()), "m4.invoice.seller_vat_missing");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void thePrintedCopyIsKeptOnTheSellersInvoice() {
        confirm.handle(new ConfirmGrn(grnId), buyer());
        UUID invoiceId = issue.handle(new IssueInvoice(List.of(grnId)), seller());
        kernel.reset();
        String key = "reports/" + SELLER + "/" + UUID.randomUUID() + ".pdf";

        // The buyer is refused: not its invoice (m4.invoice.not_seller, or not_found where it cannot lock it).
        assertThatThrownBy(() -> print.handle(new RecordInvoicePrint(invoiceId, key), buyer()))
                .isInstanceOf(ProblemException.class);
        refused(() -> print.handle(new RecordInvoicePrint(UUID.randomUUID(), key), seller()), "m4.invoice.not_found");
        refused(() -> print.handle(new RecordInvoicePrint(invoiceId, " "), seller()), "request.field.required");
        assertThat(invoices.printObjectKey(invoiceId, seller())).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        print.handle(new RecordInvoicePrint(invoiceId, key), seller());

        assertThat(invoices.printObjectKey(invoiceId, seller())).contains(key);
        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .containsExactly("INVOICE_PRINTED");
        assertThat(events(InvoicePrinted.class)).singleElement().satisfies(event -> {
            assertThat(event.invoiceId()).isEqualTo(invoiceId);
            assertThat(event.objectKey()).isEqualTo(key);
        });
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
