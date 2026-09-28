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
import lk.coopfed.knoweb.m4trading.api.RecordPaymentReceiptPrint;
import lk.coopfed.knoweb.m4trading.query.PaymentQueries;
import lk.coopfed.knoweb.m4trading.query.PaymentReceiptView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Prints the payment receipt the way {@link InvoicePrintConsumer} prints the invoice (M4-11): a
 * consumer of {@code payment_receipt.recorded.v1} on the worker role fills the kernel's
 * {@code document-a4} template (method, reference and cheque as references; one line per invoice
 * the receipt settled and one for what it left on account) and stores the PDF under the seller;
 * the key is kept by RecordPaymentReceiptPrint, from which the receipt screen's Print gets a link.
 * The copy is the receipt as recorded: money applied from the account later is on the screen, not
 * on this copy. Registered only where rendering is switched on ({@code coop-erp.report.enabled}).
 */
@Component
@ConditionalOnProperty(name = "coop-erp.report.enabled", havingValue = "true")
class PaymentReceiptPrintConsumer {

    static final String CONSUMER = "m4.payment-receipt-print";
    static final String RECEIPT_RECORDED = "payment_receipt.recorded.v1";

    private final PaymentQueries payments;
    private final PartyQueries parties;
    private final A4Renderer renderer;
    private final Formats formats;
    private final Messages messages;
    private final Handles<RecordPaymentReceiptPrint, Void> record;

    PaymentReceiptPrintConsumer(
            PaymentQueries payments,
            PartyQueries parties,
            A4Renderer renderer,
            Formats formats,
            Messages messages,
            Handles<RecordPaymentReceiptPrint, Void> record) {
        this.payments = payments;
        this.parties = parties;
        this.renderer = renderer;
        this.formats = formats;
        this.messages = messages;
        this.record = record;
    }

    @EventConsumer(types = RECEIPT_RECORDED, consumer = CONSUMER)
    public void onReceiptRecorded(JsonNode payload, ScopeContext scope) {
        UUID receiptId = UUID.fromString(payload.path("receiptId").asText());
        payments.getReceipt(receiptId, scope).ifPresent(receipt -> {
            A4Renderer.Rendered pdf =
                    renderer.render(A4Renderer.DOCUMENT_A4, model(receipt, scope), scope.locale(), scope);
            record.handle(new RecordPaymentReceiptPrint(receiptId, pdf.objectKey()), scope);
        });
    }

    Map<String, Object> model(PaymentReceiptView receipt, ScopeContext scope) {
        var locale = scope.locale();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", messages.t("m4.payment.print.title", locale));
        data.put("number", receipt.docNumberDisplay());
        data.put("date", formats.date(receipt.receivedOn()));
        data.put("from", party(receipt.sellerEntityId(), scope));
        data.put("to", party(receipt.buyerEntityId(), scope));
        List<Map<String, Object>> references = new ArrayList<>();
        references.add(reference(
                messages.t("m4.payment.print.method", locale), messages.t(methodId(receipt.method()), locale)));
        references.add(reference(
                messages.t("m4.payment.print.reference", locale),
                receipt.reference() == null ? "" : receipt.reference()));
        if (receipt.cheque() != null) {
            references.add(reference(
                    messages.t("m4.payment.print.cheque", locale),
                    receipt.cheque().bank() + " " + receipt.cheque().chequeNo() + " "
                            + formats.date(receipt.cheque().dated())));
        }
        data.put("references", references);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (PaymentReceiptView.AllocationView allocation : receipt.allocations()) {
            lines.add(line(
                    messages.t("m4.payment.print.settles", locale) + " "
                            + (allocation.invoiceNumber() == null ? "" : allocation.invoiceNumber()),
                    formats.money(allocation.amount(), locale)));
        }
        if (receipt.unappliedAmount() != null && receipt.unappliedAmount().signum() > 0) {
            lines.add(line(
                    messages.t("m4.payment.print.on_account", locale),
                    formats.money(receipt.unappliedAmount(), locale)));
        }
        data.put("lines", lines);
        data.put(
                "totals",
                List.of(reference(
                        messages.t("m4.payment.print.amount", locale), formats.money(receipt.amount(), locale))));
        return data;
    }

    /** The message id of a method, spelled out so the i18n check sees every one. */
    static String methodId(String method) {
        return switch (method) {
            case "CHEQUE" -> "m4.payment.print.method.CHEQUE";
            case "TRANSFER" -> "m4.payment.print.method.TRANSFER";
            case "DEPOSIT" -> "m4.payment.print.method.DEPOSIT";
            default -> "m4.payment.print.method.CASH";
        };
    }

    private static Map<String, Object> reference(String label, String value) {
        return Map.of("label", label, "value", value);
    }

    private static Map<String, Object> line(String description, String amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("description", description);
        row.put("quantity", "");
        row.put("unitPrice", "");
        row.put("amount", amount);
        return row;
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
}
