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
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.query.InvoiceQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Prints the tax invoice (24A section 4, "statement/ ... A4Renderer"; K-06b): a consumer of
 * {@code invoice.issued.v1} on the worker role, where Chromium runs, fills the kernel's generic
 * {@code document-a4} template with the invoice and stores the PDF under the seller (the event's
 * owner, whose OWN scope the consumer framework gives). The kernel audits REPORT_RENDERED and
 * publishes report.rendered.v1; a template of M4's own and a link on the invoice are deferred.
 * Registered only where rendering is switched on ({@code coop-erp.report.enabled}, the worker).
 */
@Component
@ConditionalOnProperty(name = "coop-erp.report.enabled", havingValue = "true")
class InvoicePrintConsumer {

    static final String CONSUMER = "m4.invoice-print";
    static final String INVOICE_ISSUED = "invoice.issued.v1";

    private final InvoiceQueries invoices;
    private final A4Renderer renderer;
    private final Formats formats;
    private final Messages messages;

    InvoicePrintConsumer(InvoiceQueries invoices, A4Renderer renderer, Formats formats, Messages messages) {
        this.invoices = invoices;
        this.renderer = renderer;
        this.formats = formats;
        this.messages = messages;
    }

    @EventConsumer(types = INVOICE_ISSUED, consumer = CONSUMER)
    public void onInvoiceIssued(JsonNode payload, ScopeContext scope) {
        UUID invoiceId = UUID.fromString(payload.path("invoiceId").asText());
        invoices.getInvoice(invoiceId, scope)
                .ifPresent(invoice ->
                        renderer.render(A4Renderer.DOCUMENT_A4, model(invoice, scope), scope.locale(), scope));
    }

    Map<String, Object> model(InvoiceView invoice, ScopeContext scope) {
        var locale = scope.locale();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", messages.t("m4.invoice.print.title", locale));
        data.put("number", invoice.docNumberDisplay());
        data.put("date", formats.date(invoice.taxPointDate()));
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
            row.put("description", line.skuId() + " (" + line.uomCode() + ")");
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
}
