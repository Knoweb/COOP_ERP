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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.A4Renderer;
import lk.coopfed.knoweb.kernel.api.Formats;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.query.InvoiceQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The invoice printed on the worker: the document-a4 model the consumer fills from the invoice (K-06b). */
class InvoicePrintConsumerTest {

    @Test
    @SuppressWarnings("unchecked")
    void theIssuedInvoiceIsRenderedAsTheGenericA4DocumentOfTheSeller() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        InvoiceView invoice = new InvoiceView(
                invoiceId,
                "D4S-INV-0000001",
                "ISSUED",
                null,
                seller,
                UUID.randomUUID(),
                "209876543-7000",
                "",
                List.of(),
                LocalDate.of(2026, 9, 27),
                LocalDate.of(2026, 10, 27),
                null,
                new BigDecimal("1280.00"),
                new BigDecimal("230.40"),
                new BigDecimal("1510.40"),
                List.of(new InvoiceView.InvoiceLineView(
                        UUID.randomUUID(),
                        1,
                        UUID.randomUUID(),
                        null,
                        "EA",
                        new BigDecimal("8"),
                        new BigDecimal("120"),
                        new BigDecimal("18"),
                        new BigDecimal("172.80"),
                        new BigDecimal("960.00"),
                        null)));
        ScopeContext scope = ScopeContext.dev(UUID.randomUUID(), seller, null);
        InvoiceQueries queries = mock(InvoiceQueries.class);
        when(queries.getInvoice(invoiceId, scope)).thenReturn(Optional.of(invoice));
        A4Renderer renderer = mock(A4Renderer.class);
        Formats formats = mock(Formats.class);
        when(formats.money(any(), any())).thenAnswer(call -> ((BigDecimal) call.getArgument(0)).toPlainString());
        when(formats.quantity(any(), any())).thenAnswer(call -> ((BigDecimal) call.getArgument(0)).toPlainString());
        when(formats.date(any())).thenAnswer(call -> call.getArgument(0).toString());
        Messages messages = mock(Messages.class);
        when(messages.t(anyString(), any())).thenAnswer(call -> call.getArgument(0));

        new InvoicePrintConsumer(queries, renderer, formats, messages)
                .onInvoiceIssued(new ObjectMapper().readTree("{\"invoiceId\":\"" + invoiceId + "\"}"), scope);

        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(renderer).render(eq(A4Renderer.DOCUMENT_A4), data.capture(), eq(scope.locale()), eq(scope));
        assertThat(data.getValue())
                .containsEntry("title", "m4.invoice.print.title")
                .containsEntry("number", "D4S-INV-0000001")
                .containsEntry("date", "2026-09-27");
        assertThat((List<Map<String, Object>>) data.getValue().get("lines"))
                .singleElement()
                .satisfies(line -> assertThat(line).containsEntry("amount", "960.00"));
        assertThat((List<Map<String, Object>>) data.getValue().get("totals"))
                .last()
                .satisfies(total -> assertThat(total).containsEntry("value", "1510.40"));
    }
}
