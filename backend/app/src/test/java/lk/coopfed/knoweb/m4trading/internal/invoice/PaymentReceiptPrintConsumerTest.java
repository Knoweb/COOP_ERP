package lk.coopfed.knoweb.m4trading.internal.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.A4Renderer;
import lk.coopfed.knoweb.kernel.api.Formats;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m4trading.api.RecordPaymentReceiptPrint;
import lk.coopfed.knoweb.m4trading.query.PaymentQueries;
import lk.coopfed.knoweb.m4trading.query.PaymentReceiptView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The payment receipt printed on the worker: the document-a4 model, as the invoice's and the credit note's. */
class PaymentReceiptPrintConsumerTest {

    @Test
    @SuppressWarnings("unchecked")
    void theRecordedReceiptIsRenderedWithItsInvoicesAndWhatItHoldsOnAccount() throws Exception {
        UUID receiptId = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        PaymentReceiptView receipt = new PaymentReceiptView(
                receiptId,
                "D4S-PRC-0000001",
                PaymentReceiptView.RECORDED,
                seller,
                UUID.randomUUID(),
                "CHEQUE",
                "REF-1",
                LocalDate.of(2026, 9, 28),
                Instant.parse("2026-09-28T04:00:00Z"),
                new BigDecimal("2000.00"),
                new BigDecimal("264.00"),
                null,
                null,
                null,
                new PaymentReceiptView.ChequeView("Bank of Ceylon", "400123", LocalDate.of(2026, 9, 28), null, null),
                List.of(new PaymentReceiptView.AllocationView(
                        UUID.randomUUID(), "D4S-INV-0000001", new BigDecimal("1736.00"))));
        ScopeContext scope = ScopeContext.dev(UUID.randomUUID(), seller, null);
        PaymentQueries queries = mock(PaymentQueries.class);
        when(queries.getReceipt(receiptId, scope)).thenReturn(Optional.of(receipt));
        A4Renderer renderer = mock(A4Renderer.class);
        when(renderer.render(anyString(), any(), any(), any()))
                .thenReturn(new A4Renderer.Rendered(
                        UUID.randomUUID(),
                        "reports/" + seller + "/prc.pdf",
                        URI.create("http://s/prc.pdf"),
                        Instant.now(),
                        10,
                        "ab"));
        Formats formats = mock(Formats.class);
        when(formats.money(any(), any())).thenAnswer(call -> ((BigDecimal) call.getArgument(0)).toPlainString());
        when(formats.date(any())).thenAnswer(call -> call.getArgument(0).toString());
        Messages messages = mock(Messages.class);
        when(messages.t(anyString(), any())).thenAnswer(call -> call.getArgument(0));
        Handles<RecordPaymentReceiptPrint, Void> record = mock(Handles.class);

        new PaymentReceiptPrintConsumer(queries, mock(PartyQueries.class), renderer, formats, messages, record)
                .onReceiptRecorded(new ObjectMapper().readTree("{\"receiptId\":\"" + receiptId + "\"}"), scope);

        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(renderer).render(eq(A4Renderer.DOCUMENT_A4), data.capture(), eq(scope.locale()), eq(scope));
        assertThat(data.getValue())
                .containsEntry("title", "m4.payment.print.title")
                .containsEntry("number", "D4S-PRC-0000001")
                .containsEntry("date", "2026-09-28");
        assertThat((List<Map<String, Object>>) data.getValue().get("references"))
                .extracting(ref -> ref.get("value"))
                .containsExactly("m4.payment.print.method.CHEQUE", "REF-1", "Bank of Ceylon 400123 2026-09-28");
        assertThat((List<Map<String, Object>>) data.getValue().get("lines"))
                .extracting(line -> line.get("amount"))
                .containsExactly("1736.00", "264.00");
        assertThat((List<Map<String, Object>>) data.getValue().get("totals"))
                .singleElement()
                .satisfies(total -> assertThat(total).containsEntry("value", "2000.00"));
        verify(record).handle(new RecordPaymentReceiptPrint(receiptId, "reports/" + seller + "/prc.pdf"), scope);
    }
}
