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
import lk.coopfed.knoweb.m4trading.api.RecordInvoicePrint;
import lk.coopfed.knoweb.m4trading.query.InvoiceQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Prints the tax invoice (24A section 4, "statement/ ... A4Renderer"; K-06b): a consumer of
 * {@code invoice.issued.v1} on the worker role, where Chromium runs, fills the kernel's generic
 * {@code document-a4} template with the invoice and stores the PDF under the seller (the event's
 * owner, whose OWN scope the consumer framework gives). The kernel audits REPORT_RENDERED and
 * publishes report.rendered.v1; the PDF's key is kept on the invoice by RecordInvoicePrint (M4-11),
 * from which the invoice screen's Print button gets a link. A template of M4's own is deferred.
 *
 * <p>The template is generic, so the names are filled here: the seller and the buyer by their
 * legal name and code from M1 (the seller's own row; the buyer's row of the party directory, which
 * a trading counterparty may read), each item by its code and name from M2, all in the language of
 * the rendering (the scope's). M1 holds no postal address of an entity, only of its locations, so
 * the parties show no address. A name that cannot be read prints the id, as before, rather than
 * leaving the invoice unprinted.
 * Registered only where rendering is switched on ({@code coop-erp.report.enabled}, the worker).
 */
@Component
@ConditionalOnProperty(name = "coop-erp.report.enabled", havingValue = "true")
class InvoicePrintConsumer {

    static final String CONSUMER = "m4.invoice-print";
    static final String INVOICE_ISSUED = "invoice.issued.v1";

    private final InvoiceQueries invoices;
    private final PartyQueries parties;
    private final CatalogueQueries catalogue;
    private final A4Renderer renderer;
    private final Formats formats;
    private final Messages messages;
    private final Handles<RecordInvoicePrint, Void> record;

    InvoicePrintConsumer(
            InvoiceQueries invoices,
            PartyQueries parties,
            CatalogueQueries catalogue,
            A4Renderer renderer,
            Formats formats,
            Messages messages,
            Handles<RecordInvoicePrint, Void> record) {
        this.invoices = invoices;
        this.parties = parties;
        this.catalogue = catalogue;
        this.renderer = renderer;
        this.formats = formats;
        this.messages = messages;
        this.record = record;
    }

    @EventConsumer(types = INVOICE_ISSUED, consumer = CONSUMER)
    public void onInvoiceIssued(JsonNode payload, ScopeContext scope) {
        UUID invoiceId = UUID.fromString(payload.path("invoiceId").asText());
        invoices.getInvoice(invoiceId, scope).ifPresent(invoice -> {
            A4Renderer.Rendered pdf =
                    renderer.render(A4Renderer.DOCUMENT_A4, model(invoice, scope), scope.locale(), scope);
            record.handle(new RecordInvoicePrint(invoiceId, pdf.objectKey()), scope);
        });
    }

    Map<String, Object> model(InvoiceView invoice, ScopeContext scope) {
        var locale = scope.locale();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", messages.t("m4.invoice.print.title", locale));
        data.put("number", invoice.docNumberDisplay());
        data.put("date", formats.date(invoice.taxPointDate()));
        data.put("from", party(invoice.sellerEntityId(), scope));
        data.put("to", party(invoice.buyerEntityId(), scope));
        data.put(
                "references",
                List.of(
                        Map.of(
                                "label",
                                messages.t("m4.invoice.print.due", locale),
                                "value",
                                formats.date(invoice.dueDate())),
                        Map.of(
                                "label",
                                messages.t("m4.invoice.print.seller_vat", locale),
                                "value",
                                invoice.sellerVatNo()),
                        Map.of(
                                "label",
                                messages.t("m4.invoice.print.buyer_vat", locale),
                                "value",
                                invoice.buyerVatNo() == null ? "" : invoice.buyerVatNo())));
        List<Map<String, Object>> lines = new ArrayList<>();
        for (InvoiceView.InvoiceLineView line : invoice.lines()) {
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
                                formats.money(invoice.netAmount(), locale)),
                        Map.of(
                                "label",
                                messages.t("m4.invoice.print.vat", locale),
                                "value",
                                formats.money(invoice.taxAmount(), locale)),
                        Map.of(
                                "label",
                                messages.t("m4.invoice.print.gross", locale),
                                "value",
                                formats.money(invoice.grossAmount(), locale))));
        return data;
    }

    /** A party of the document as the template shows it: the legal name, and the code below it. */
    private Map<String, Object> party(UUID entityId, ScopeContext scope) {
        Map<String, Object> party = new LinkedHashMap<>();
        var entity = parties.getEntity(entityId, scope);
        if (entity.isEmpty()) {
            party.put("title", entityId.toString());
            party.put("lines", "");
            return party;
        }
        EntityView view = entity.get();
        party.put("title", inLanguage(scope.lang(), view.legalNameEn(), view.legalNameSi(), view.legalNameTa()));
        party.put("lines", view.entityCode() == null ? "" : view.entityCode());
        return party;
    }

    /** An item as the line shows it: its code and name, or its id when the catalogue cannot say. */
    private String item(UUID skuId, ScopeContext scope) {
        return catalogue
                .getSku(skuId, scope)
                .map((SkuView sku) ->
                        sku.skuCode() + " " + inLanguage(scope.lang(), sku.nameEn(), sku.nameSi(), sku.nameTa()))
                .orElse(skuId.toString());
    }

    /** The name in the document's language; English when that language has none. */
    static String inLanguage(String lang, String en, String si, String ta) {
        String local = "si".equals(lang) ? si : "ta".equals(lang) ? ta : null;
        return local == null || local.isBlank() ? en : local;
    }
}
