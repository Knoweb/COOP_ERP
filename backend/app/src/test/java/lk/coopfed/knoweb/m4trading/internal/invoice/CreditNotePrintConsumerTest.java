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
import java.time.ZoneId;
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
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m4trading.api.RecordCreditNotePrint;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.query.CreditNoteQueries;
import lk.coopfed.knoweb.m4trading.query.CreditNoteView;
import lk.coopfed.knoweb.m4trading.query.InvoiceView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The credit note printed on the worker: the document-a4 model, as the invoice's (K-06b). */
class CreditNotePrintConsumerTest {

    @Test
    @SuppressWarnings("unchecked")
    void theIssuedCreditNoteIsRenderedAsTheGenericA4DocumentOfTheSeller() throws Exception {
        UUID creditNoteId = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        CreditNoteView note = new CreditNoteView(
                creditNoteId,
                "D4S-CN-0000001",
                "ISSUED",
                UUID.randomUUID(),
                "D4S-INV-0000001",
                UUID.randomUUID(),
                seller,
                buyer,
                "Two bags short",
                Instant.parse("2026-09-28T04:00:00Z"),
                new BigDecimal("240.00"),
                new BigDecimal("43.20"),
                new BigDecimal("283.20"),
                List.of(new InvoiceView.InvoiceLineView(
                        UUID.randomUUID(),
                        1,
                        sku,
                        null,
                        "EA",
                        new BigDecimal("2"),
                        new BigDecimal("120"),
                        new BigDecimal("18"),
                        new BigDecimal("43.20"),
                        new BigDecimal("240.00"),
                        null)));
        ScopeContext scope = ScopeContext.dev(UUID.randomUUID(), seller, null);
        CreditNoteQueries queries = mock(CreditNoteQueries.class);
        when(queries.getCreditNote(creditNoteId, scope)).thenReturn(Optional.of(note));
        A4Renderer renderer = mock(A4Renderer.class);
        when(renderer.render(anyString(), any(), any(), any()))
                .thenReturn(new A4Renderer.Rendered(
                        UUID.randomUUID(),
                        "reports/" + seller + "/cn.pdf",
                        URI.create("http://s/cn.pdf"),
                        Instant.now(),
                        10,
                        "ab"));
        Formats formats = mock(Formats.class);
        when(formats.money(any(), any())).thenAnswer(call -> ((BigDecimal) call.getArgument(0)).toPlainString());
        when(formats.quantity(any(), any())).thenAnswer(call -> ((BigDecimal) call.getArgument(0)).toPlainString());
        when(formats.date(any())).thenAnswer(call -> call.getArgument(0).toString());
        Messages messages = mock(Messages.class);
        when(messages.t(anyString(), any())).thenAnswer(call -> call.getArgument(0));
        Handles<RecordCreditNotePrint, Void> record = mock(Handles.class);
        TradingClock clock = mock(TradingClock.class);
        when(clock.zone()).thenReturn(ZoneId.of("Asia/Colombo"));

        new CreditNotePrintConsumer(
                        queries,
                        mock(PartyQueries.class),
                        mock(CatalogueQueries.class),
                        renderer,
                        formats,
                        messages,
                        record,
                        clock)
                .onCreditNoteIssued(new ObjectMapper().readTree("{\"creditNoteId\":\"" + creditNoteId + "\"}"), scope);

        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(renderer).render(eq(A4Renderer.DOCUMENT_A4), data.capture(), eq(scope.locale()), eq(scope));
        assertThat(data.getValue())
                .containsEntry("title", "m4.creditnote.print.title")
                .containsEntry("number", "D4S-CN-0000001")
                .containsEntry("date", "2026-09-28");
        assertThat((List<Map<String, Object>>) data.getValue().get("references"))
                .extracting(ref -> ref.get("value"))
                .containsExactly("D4S-INV-0000001", "Two bags short");
        assertThat((List<Map<String, Object>>) data.getValue().get("totals"))
                .last()
                .satisfies(total -> assertThat(total).containsEntry("value", "283.20"));
        verify(record).handle(new RecordCreditNotePrint(creditNoteId, "reports/" + seller + "/cn.pdf"), scope);
    }
}
