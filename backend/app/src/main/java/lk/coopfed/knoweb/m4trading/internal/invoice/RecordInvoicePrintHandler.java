package lk.coopfed.knoweb.m4trading.internal.invoice;

import java.util.LinkedHashMap;
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
import lk.coopfed.knoweb.m4trading.api.InvoicePrinted;
import lk.coopfed.knoweb.m4trading.api.RecordInvoicePrint;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RecordInvoicePrint (M4-11). Guards, in order: an OWN scope; an object key; the seller's own
 * issued invoice. Mutation: {@code doc_invoice.print_object_key} (a later print replaces the
 * earlier one's key; the PDFs stay in the object store). Audit INVOICE_PRINTED; event
 * invoice.printed.v1. The permission is the invoice's own, {@code bil.invoice.issue}: the print
 * consumer runs it in the seller's scope as part of issuing.
 */
@Service
@CommandHandler(permission = "bil.invoice.issue")
public class RecordInvoicePrintHandler implements Handles<RecordInvoicePrint, Void> {

    static final String AUDIT_PRINTED = "INVOICE_PRINTED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final AuditFacade audit;
    private final EventPublisher events;

    RecordInvoicePrintHandler(
            JdbcTemplate jdbc, DocumentBaseRepository documents, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Void handle(RecordInvoicePrint command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireOwnScope(scope);
        UUID invoiceId = TradingGuards.required(command.invoiceId(), "invoiceId");
        String objectKey = TradingGuards.required(command.objectKey(), "objectKey");
        DocumentRecord invoice = documents
                .findById(invoiceId)
                .filter(document -> IssueInvoiceHandler.INV.equals(document.docTypeCode()))
                .orElseThrow(() -> new ProblemException("m4.invoice.not_found"));
        if (!invoice.ownerEntityId().equals(scope.entityId())) {
            throw new ProblemException("m4.invoice.not_seller");
        }
        if (invoice.docNumberDisplay() == null) {
            throw new ProblemException("m4.invoice.not_issued");
        }

        String before = jdbc.queryForObject(
                "select print_object_key from trading.doc_invoice where document_id = ?", String.class, invoiceId);
        jdbc.update("update trading.doc_invoice set print_object_key = ? where document_id = ?", objectKey, invoiceId);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("invoiceId", invoiceId);
        after.put("objectKey", objectKey);
        audit.record(
                AUDIT_PRINTED,
                Subject.of("invoice", invoiceId),
                before == null ? null : Map.of("objectKey", before),
                after,
                scope);
        events.publish(new InvoicePrinted(invoiceId, scope.entityId(), objectKey));
        return null;
    }
}
