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
import lk.coopfed.knoweb.m4trading.api.CreditNotePrinted;
import lk.coopfed.knoweb.m4trading.api.RecordCreditNotePrint;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RecordCreditNotePrint (M4-11), the credit note's twin of {@link RecordInvoicePrintHandler}.
 * Guards, in order: an OWN scope; an object key; the seller's own issued credit note. Mutation:
 * {@code doc_credit_note.print_object_key}. Audit CREDIT_NOTE_PRINTED; event credit_note.printed.v1.
 * Run by the print consumer in the seller's scope, with no user (so no second factor is asked).
 */
@Service
@CommandHandler(permission = "bil.creditnote.issue")
public class RecordCreditNotePrintHandler implements Handles<RecordCreditNotePrint, Void> {

    static final String AUDIT_PRINTED = "CREDIT_NOTE_PRINTED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final AuditFacade audit;
    private final EventPublisher events;

    RecordCreditNotePrintHandler(
            JdbcTemplate jdbc, DocumentBaseRepository documents, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Void handle(RecordCreditNotePrint command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireOwnScope(scope);
        UUID creditNoteId = TradingGuards.required(command.creditNoteId(), "creditNoteId");
        String objectKey = TradingGuards.required(command.objectKey(), "objectKey");
        DocumentRecord creditNote = documents
                .findById(creditNoteId)
                .filter(document -> IssueCreditNoteHandler.CN.equals(document.docTypeCode()))
                .orElseThrow(() -> new ProblemException("m4.creditnote.not_found"));
        if (!creditNote.ownerEntityId().equals(scope.entityId())) {
            throw new ProblemException("m4.creditnote.not_seller");
        }

        String before = jdbc.queryForObject(
                "select print_object_key from trading.doc_credit_note where document_id = ?",
                String.class,
                creditNoteId);
        jdbc.update(
                "update trading.doc_credit_note set print_object_key = ? where document_id = ?",
                objectKey,
                creditNoteId);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("creditNoteId", creditNoteId);
        after.put("objectKey", objectKey);
        audit.record(
                AUDIT_PRINTED,
                Subject.of("credit_note", creditNoteId),
                before == null ? null : Map.of("objectKey", before),
                after,
                scope);
        events.publish(new CreditNotePrinted(creditNoteId, scope.entityId(), objectKey));
        return null;
    }
}
