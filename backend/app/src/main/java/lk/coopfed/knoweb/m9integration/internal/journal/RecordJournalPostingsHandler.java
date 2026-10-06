package lk.coopfed.knoweb.m9integration.internal.journal;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m9integration.api.JournalPostingsRecorded;
import lk.coopfed.knoweb.m9integration.api.RecordJournalPostings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RecordJournalPostings (29A section 6.2, the input of the JournalBuilder): the postings of one
 * issued document, from the journal consumer, held in {@code journal_posting} for the entity's
 * next export.
 *
 * <p>Guards: the OWN scope of an entity ({@code m9.journal.own_required}); a document, its type
 * and number, a business date, and on every posting the side, both roles and an amount
 * ({@code m9.journal.postings_malformed}). A document whose postings this entity already holds (the
 * event redelivered) is left alone and answers 0: nothing is recorded, audited or published twice.
 *
 * <p>Mutation: one row per posting, numbered in the order of the event. The owning module
 * computed the amount and named the roles; a negative amount (a reversal written as a negated
 * receipt) is recorded with its roles exchanged and the amount positive, which is the same entry.
 * A zero amount carries nothing and is left out. Audit JOURNAL_POSTINGS_RECORDED; event
 * journal.postings_recorded.v1. Permission: the system records it for the owning module with no
 * user, so none is checked there; int.journal.export is the code of the export it feeds.
 */
@Service
@CommandHandler(permission = "int.journal.export")
class RecordJournalPostingsHandler implements Handles<RecordJournalPostings, Integer> {

    static final String AUDIT_RECORDED = "JOURNAL_POSTINGS_RECORDED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    RecordJournalPostingsHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Integer handle(RecordJournalPostings command, ScopeContext scope) {
        if (scope.policyClass() != PolicyClass.OWN || scope.entityId() == null) {
            throw new ProblemException("m9.journal.own_required");
        }
        if (command == null
                || command.documentId() == null
                || blank(command.docTypeCode())
                || blank(command.docNumberDisplay())
                || command.businessDate() == null
                || command.postings() == null
                || command.postings().stream().anyMatch(RecordJournalPostingsHandler::malformed)) {
            throw new ProblemException("m9.journal.postings_malformed");
        }
        // Held already by this entity, named explicitly: the seller's and the buyer's postings of
        // one invoice share its document id and are two entities' books (wave 2, M9-02 (3)), so the
        // owner is part of the key, not left to the read policy.
        Integer held = jdbc.queryForObject(
                "select count(*) from integration.journal_posting where owner_entity_id = ? and document_id = ?",
                Integer.class,
                scope.entityId(),
                command.documentId());
        if (held != null && held > 0) {
            return 0;
        }

        int seq = 0;
        BigDecimal total = BigDecimal.ZERO;
        Timestamp now = Timestamp.from(clock.instant());
        for (RecordJournalPostings.Line line : command.postings()) {
            if (line.amount().signum() == 0) {
                continue;
            }
            boolean negated = line.amount().signum() < 0;
            BigDecimal amount = line.amount().abs();
            seq++;
            total = total.add(amount);
            jdbc.update(
                    """
                    insert into integration.journal_posting
                           (posting_id, owner_entity_id, document_id, seq, doc_type_code, doc_number_display,
                            line_kind, side, debit_role, credit_role, amount_source, amount, business_date, recorded_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    Ids.next(),
                    scope.entityId(),
                    command.documentId(),
                    seq,
                    command.docTypeCode(),
                    command.docNumberDisplay(),
                    line.lineKind(),
                    line.side(),
                    negated ? line.creditRole() : line.debitRole(),
                    negated ? line.debitRole() : line.creditRole(),
                    line.amountSource() == null ? "" : line.amountSource(),
                    amount,
                    Date.valueOf(command.businessDate()),
                    now);
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("docType", command.docTypeCode());
        after.put("docNumber", command.docNumberDisplay());
        after.put("businessDate", command.businessDate().toString());
        after.put("postings", seq);
        after.put("amount", total);
        audit.record(AUDIT_RECORDED, Subject.of("document", command.documentId()), null, after, scope);
        events.publish(new JournalPostingsRecorded(command.documentId(), command.docTypeCode(), seq));
        return seq;
    }

    private static boolean malformed(RecordJournalPostings.Line line) {
        return line == null
                || blank(line.lineKind())
                || !("SELLER".equals(line.side()) || "BUYER".equals(line.side()))
                || blank(line.debitRole())
                || blank(line.creditRole())
                || line.amount() == null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
