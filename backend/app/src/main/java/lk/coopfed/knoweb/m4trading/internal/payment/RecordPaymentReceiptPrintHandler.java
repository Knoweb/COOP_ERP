package lk.coopfed.knoweb.m4trading.internal.payment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m4trading.api.PaymentReceiptPrinted;
import lk.coopfed.knoweb.m4trading.api.RecordPaymentReceiptPrint;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RecordPaymentReceiptPrint (M4-11), the receipt's twin of RecordInvoicePrint and
 * RecordCreditNotePrint. Guards, in order: an OWN scope; an object key; the seller's own issued
 * PRC. Mutation: {@code doc_payment_receipt.print_object_key} (V0007). Audit
 * PAYMENT_RECEIPT_PRINTED; event payment_receipt.printed.v1. Run by the print consumer in the
 * seller's scope, with no user.
 */
@Service
@CommandHandler(permission = "bil.payment.record")
public class RecordPaymentReceiptPrintHandler implements Handles<RecordPaymentReceiptPrint, Void> {

    static final String AUDIT_PRINTED = "PAYMENT_RECEIPT_PRINTED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final AuditFacade audit;
    private final EventPublisher events;

    RecordPaymentReceiptPrintHandler(
            JdbcTemplate jdbc, DocumentBaseRepository documents, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Void handle(RecordPaymentReceiptPrint command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireOwnScope(scope);
        UUID receiptId = TradingGuards.required(command.receiptId(), "receiptId");
        String objectKey = TradingGuards.required(command.objectKey(), "objectKey");
        DocumentRecord receipt = documents
                .findById(receiptId)
                .filter(document -> RecordPaymentReceiptHandler.PRC.equals(document.docTypeCode()))
                .orElseThrow(() -> new ProblemException("m4.payment.not_found"));
        if (!receipt.ownerEntityId().equals(scope.entityId())) {
            throw new ProblemException("m4.payment.not_seller");
        }

        List<String> before = jdbc.queryForList(
                "select print_object_key from trading.doc_payment_receipt where document_id = ?",
                String.class,
                receiptId);
        jdbc.update(
                "update trading.doc_payment_receipt set print_object_key = ? where document_id = ?",
                objectKey,
                receiptId);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("receiptId", receiptId);
        after.put("objectKey", objectKey);
        String previous = before.isEmpty() ? null : before.get(0);
        audit.record(
                AUDIT_PRINTED,
                Subject.of("payment_receipt", receiptId),
                previous == null ? null : Map.of("objectKey", previous),
                after,
                scope);
        events.publish(new PaymentReceiptPrinted(receiptId, scope.entityId(), objectKey));
        return null;
    }
}
