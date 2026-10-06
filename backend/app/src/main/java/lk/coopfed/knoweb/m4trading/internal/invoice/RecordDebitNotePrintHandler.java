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
import lk.coopfed.knoweb.m4trading.api.DebitNotePrinted;
import lk.coopfed.knoweb.m4trading.api.RecordDebitNotePrint;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RecordDebitNotePrint, the debit note's twin of {@link RecordInvoicePrintHandler}.
 * Guards, in order: an OWN scope; an object key; the seller's own issued debit note. Mutation:
 * {@code doc_debit_note.print_object_key}. Audit DEBIT_NOTE_PRINTED; event debit_note.printed.v1.
 * Run by the print consumer in the seller's scope, with no user (so no second factor is asked).
 */
@Service
@CommandHandler(permission = "bil.debitnote.issue")
public class RecordDebitNotePrintHandler implements Handles<RecordDebitNotePrint, Void> {

    static final String AUDIT_PRINTED = "DEBIT_NOTE_PRINTED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final AuditFacade audit;
    private final EventPublisher events;

    RecordDebitNotePrintHandler(
            JdbcTemplate jdbc, DocumentBaseRepository documents, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Void handle(RecordDebitNotePrint command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireOwnScope(scope);
        UUID debitNoteId = TradingGuards.required(command.debitNoteId(), "debitNoteId");
        String objectKey = TradingGuards.required(command.objectKey(), "objectKey");
        DocumentRecord debitNote = documents
                .findById(debitNoteId)
                .filter(document -> IssueDebitNoteHandler.DN2.equals(document.docTypeCode()))
                .orElseThrow(() -> new ProblemException("m4.debitnote.not_found"));
        if (!debitNote.ownerEntityId().equals(scope.entityId())) {
            throw new ProblemException("m4.debitnote.not_seller");
        }

        String before = jdbc.queryForObject(
                "select print_object_key from trading.doc_debit_note where document_id = ?", String.class, debitNoteId);
        jdbc.update(
                "update trading.doc_debit_note set print_object_key = ? where document_id = ?", objectKey, debitNoteId);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("debitNoteId", debitNoteId);
        after.put("objectKey", objectKey);
        audit.record(
                AUDIT_PRINTED,
                Subject.of("debit_note", debitNoteId),
                before == null ? null : Map.of("objectKey", before),
                after,
                scope);
        events.publish(new DebitNotePrinted(debitNoteId, scope.entityId(), objectKey));
        return null;
    }
}
