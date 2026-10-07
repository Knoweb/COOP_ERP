package lk.coopfed.knoweb.m4trading.internal.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.A4Renderer;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.Formats;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.EntityView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m4trading.api.RecordDebitNotePrint;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.query.DebitNoteQueries;
import lk.coopfed.knoweb.m4trading.query.DebitNoteView;
import lk.coopfed.knoweb.m4trading.query.InvoiceView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Prints the debit note the way {@link InvoicePrintConsumer} prints the invoice.
 */
@Component
@ConditionalOnProperty(name = "coop-erp.report.enabled", havingValue = "true")
class DebitNotePrintConsumer {

    static final String CONSUMER = "m4.debit-note-print";
    static final String DEBIT_NOTE_ISSUED = "debit_note.issued.v1";

    private final DebitNoteQueries debitNotes;
    private final PartyQueries parties;
    private final CatalogueQueries catalogue;
    private final A4Renderer renderer;
    private final Formats formats;
    private final Messages messages;
    private final Handles<RecordDebitNotePrint, Void> record;
    private final TradingClock clock;

    DebitNotePrintConsumer(
            DebitNoteQueries debitNotes,
            PartyQueries parties,
            CatalogueQueries catalogue,
            A4Renderer renderer,
            Formats formats,
            Messages messages,
            Handles<RecordDebitNotePrint, Void> record,
            TradingClock clock) {
        this.clock = clock;
        this.debitNotes = debitNotes;
        this.parties = parties;
        this.catalogue = catalogue;
        this.renderer = renderer;
        this.formats = formats;
        this.messages = messages;
        this.record = record;
    }

    @EventConsumer(types = DEBIT_NOTE_ISSUED, consumer = CONSUMER)
    public void onDebitNoteIssued(JsonNode payload, ScopeContext scope) {
        UUID debitNoteId = UUID.fromString(payload.path("debitNoteId").asText());
        debitNotes.getDebitNote(debitNoteId, scope).ifPresent(note -> {
            A4Renderer.Rendered pdf =
                    renderer.render(A4Renderer.DOCUMENT_A4, model(note, scope), scope.locale(), scope);
            record.handle(new RecordDebitNotePrint(debitNoteId, pdf.objectKey()), scope);
        });
    }

    Map<String, Object> model(DebitNoteView note, ScopeContext scope) {
        var locale = scope.locale();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", messages.t("m4.debitnote.print.title", locale));
        data.put("number", note.docNumberDisplay());
        data.put(
                "date",
                note.issuedAt() == null
                        ? ""
                        : formats.date(java.time.LocalDate.ofInstant(note.issuedAt(), clock.zone())));
        data.put("from", party(note.sellerEntityId(), scope));
        data.put("to", party(note.buyerEntityId(), scope));
        data.put(
                "references",
                List.of(
                        Map.of(
                                "label",
                                messages.t("m4.debitnote.print.invoice", locale),
                                "value",
                                note.invoiceDocNumberDisplay() == null ? "" : note.invoiceDocNumberDisplay()),
                        Map.of(
                                "label",
                                messages.t("m4.debitnote.print.reason", locale),
                                "value",
                                note.reason() == null ? "" : note.reason())));
        List<Map<String, Object>> lines = new ArrayList<>();
        for (InvoiceView.InvoiceLineView line : note.lines()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("description", item(line.skuId(), scope) + " (" + line.uomCode() + ")");
            row.put("quantity", formats.quantity(line.qty(), locale));
            row.put("unitPrice", formats.money(line.unitPrice(), locale));
            row.put("amount", formats.money(line.lineTotal(), locale));
            lines.add(row);
        }
        data.put("lines", lines);
        data.put(
                "totals",
                List.of(
                        Map.of(
                                "label",
                                messages.t("m4.invoice.print.net", locale),
                                "value",
                                formats.money(note.netAmount(), locale)),
                        Map.of(
                                "label",
                                messages.t("m4.invoice.print.vat", locale),
                                "value",
                                formats.money(note.taxAmount(), locale)),
                        Map.of(
                                "label",
                                messages.t("m4.debitnote.print.gross", locale),
                                "value",
                                formats.money(note.grossAmount(), locale))));
        return data;
    }

    private Map<String, Object> party(UUID entityId, ScopeContext scope) {
        Map<String, Object> party = new LinkedHashMap<>();
        var entity = parties.getEntity(entityId, scope);
        if (entity.isEmpty()) {
            party.put("title", entityId.toString());
            party.put("lines", "");
            return party;
        }
        EntityView view = entity.get();
        party.put(
                "title",
                InvoicePrintConsumer.inLanguage(
                        scope.lang(), view.legalNameEn(), view.legalNameSi(), view.legalNameTa()));
        party.put("lines", view.entityCode() == null ? "" : view.entityCode());
        return party;
    }

    private String item(UUID skuId, ScopeContext scope) {
        return catalogue
                .getSku(skuId, scope)
                .map((SkuView sku) -> sku.skuCode() + " "
                        + InvoicePrintConsumer.inLanguage(scope.lang(), sku.nameEn(), sku.nameSi(), sku.nameTa()))
                .orElse(skuId.toString());
    }
}
