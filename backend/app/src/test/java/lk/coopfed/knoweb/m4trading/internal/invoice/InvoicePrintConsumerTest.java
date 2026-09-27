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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.A4Renderer;
import lk.coopfed.knoweb.kernel.api.Formats;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.EntityView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m4trading.api.RecordInvoicePrint;
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
        UUID buyer = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        InvoiceView invoice = new InvoiceView(
                invoiceId,
                "D4S-INV-0000001",
                "ISSUED",
                null,
                seller,
                buyer,
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
                        sku,
                        null,
                        "EA",
                        new BigDecimal("8"),
                        new BigDecimal("120"),
                        new BigDecimal("18"),
                        new BigDecimal("172.80"),
                        new BigDecimal("960.00"),
                        null)));
        ScopeContext scope = sinhala(ScopeContext.dev(UUID.randomUUID(), seller, null));
        InvoiceQueries queries = mock(InvoiceQueries.class);
        when(queries.getInvoice(invoiceId, scope)).thenReturn(Optional.of(invoice));
        A4Renderer renderer = mock(A4Renderer.class);
        Formats formats = mock(Formats.class);
        when(formats.money(any(), any())).thenAnswer(call -> ((BigDecimal) call.getArgument(0)).toPlainString());
        when(formats.quantity(any(), any())).thenAnswer(call -> ((BigDecimal) call.getArgument(0)).toPlainString());
        when(formats.date(any())).thenAnswer(call -> call.getArgument(0).toString());
        Messages messages = mock(Messages.class);
        when(messages.t(anyString(), any())).thenAnswer(call -> call.getArgument(0));

        Handles<RecordInvoicePrint, Void> record = mock(Handles.class);
        when(renderer.render(anyString(), any(), any(), any()))
                .thenReturn(new A4Renderer.Rendered(
                        UUID.randomUUID(),
                        "reports/" + seller + "/r.pdf",
                        URI.create("http://s/r.pdf"),
                        Instant.now(),
                        10,
                        "ab"));

        PartyQueries parties = mock(PartyQueries.class);
        when(parties.getEntity(seller, scope))
                .thenReturn(Optional.of(entity(seller, "FED", "Cooperative Federation", "සමුපකාර සම්මේලනය")));
        // The buyer's row of the party directory: since M1's V0014 it carries the code too.
        when(parties.getEntity(buyer, scope))
                .thenReturn(Optional.of(entity(buyer, "D101", "Wayamba Distributors", null)));
        CatalogueQueries catalogue = mock(CatalogueQueries.class);
        SkuView skuView = mock(SkuView.class);
        when(skuView.skuCode()).thenReturn("SKU-RICE5");
        when(skuView.nameEn()).thenReturn("Samba rice 5 kg");
        when(skuView.nameSi()).thenReturn("සම්බා සහල් 5 kg");
        when(catalogue.getSku(sku, scope)).thenReturn(Optional.of(skuView));

        new InvoicePrintConsumer(queries, parties, catalogue, renderer, formats, messages, record)
                .onInvoiceIssued(new ObjectMapper().readTree("{\"invoiceId\":\"" + invoiceId + "\"}"), scope);

        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(renderer).render(eq(A4Renderer.DOCUMENT_A4), data.capture(), eq(scope.locale()), eq(scope));
        assertThat(data.getValue())
                .containsEntry("title", "m4.invoice.print.title")
                .containsEntry("number", "D4S-INV-0000001")
                .containsEntry("date", "2026-09-27");
        assertThat((List<Map<String, Object>>) data.getValue().get("lines"))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .containsEntry("amount", "960.00")
                        // The item by its code and name in the invoice's language, not its id.
                        .containsEntry("description", "SKU-RICE5 සම්බා සහල් 5 kg (EA)"));
        // The seller and the buyer by their legal names (English where Sinhala is missing) and codes.
        assertThat(data.getValue().get("from")).isEqualTo(Map.of("title", "සමුපකාර සම්මේලනය", "lines", "FED"));
        assertThat(data.getValue().get("to")).isEqualTo(Map.of("title", "Wayamba Distributors", "lines", "D101"));
        // The VAT numbers stay where they were, among the references.
        assertThat((List<Map<String, Object>>) data.getValue().get("references"))
                .extracting(ref -> ref.get("value"))
                .contains("209876543-7000");
        assertThat((List<Map<String, Object>>) data.getValue().get("totals"))
                .last()
                .satisfies(total -> assertThat(total).containsEntry("value", "1510.40"));
        // The PDF's key is kept on the invoice for the Print button (M4-11).
        verify(record).handle(new RecordInvoicePrint(invoiceId, "reports/" + seller + "/r.pdf"), scope);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPartyOrItemThatCannotBeReadPrintsItsIdRatherThanNothing() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        InvoiceView invoice = new InvoiceView(
                invoiceId,
                "X-INV-1",
                "ISSUED",
                null,
                seller,
                UUID.randomUUID(),
                "1",
                "",
                List.of(),
                LocalDate.of(2026, 9, 27),
                LocalDate.of(2026, 10, 27),
                null,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ONE,
                List.of(new InvoiceView.InvoiceLineView(
                        UUID.randomUUID(),
                        1,
                        sku,
                        null,
                        "EA",
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ONE,
                        null)));
        ScopeContext scope = ScopeContext.dev(UUID.randomUUID(), seller, null);
        InvoiceQueries queries = mock(InvoiceQueries.class);
        when(queries.getInvoice(invoiceId, scope)).thenReturn(Optional.of(invoice));
        Formats formats = mock(Formats.class);
        when(formats.money(any(), any())).thenReturn("1.00");
        when(formats.quantity(any(), any())).thenReturn("1");
        when(formats.date(any())).thenReturn("d");
        Messages messages = mock(Messages.class);
        when(messages.t(anyString(), any())).thenAnswer(call -> call.getArgument(0));

        var data = new InvoicePrintConsumer(
                        queries,
                        mock(PartyQueries.class),
                        mock(CatalogueQueries.class),
                        mock(A4Renderer.class),
                        formats,
                        messages,
                        mock(Handles.class))
                .model(invoice, scope);

        assertThat(((Map<String, Object>) data.get("from")).get("title")).isEqualTo(seller.toString());
        assertThat((List<Map<String, Object>>) data.get("lines"))
                .singleElement()
                .satisfies(line -> assertThat(line).containsEntry("description", sku + " (EA)"));
    }

    private static EntityView entity(UUID id, String code, String en, String si) {
        return new EntityView(id, code, null, en, si, null, null, null, null, null, null, null, null, null);
    }

    private static ScopeContext sinhala(ScopeContext scope) {
        return new ScopeContext(
                scope.userId(),
                scope.deviceId(),
                scope.homeEntityId(),
                scope.scopes(),
                scope.activeScope(),
                scope.policyClass(),
                scope.grantedEntities(),
                scope.mfaAt(),
                Locale.forLanguageTag("si"),
                scope.correlationId());
    }
}
