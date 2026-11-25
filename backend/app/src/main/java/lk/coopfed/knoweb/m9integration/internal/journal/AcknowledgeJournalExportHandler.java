package lk.coopfed.knoweb.m9integration.internal.journal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m9integration.api.AcknowledgeJournalExport;
import lk.coopfed.knoweb.m9integration.api.JournalExportAcknowledged;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ACC-03: acknowledge an existing GENERATED journal export.
 * Export files and journal lines remain immutable.
 */
@Service
@CommandHandler(permission = "int.journal.ack")
class AcknowledgeJournalExportHandler implements Handles<AcknowledgeJournalExport, UUID> {

    static final String AUDIT_ACKNOWLEDGED = "JOURNAL_EXPORT_ACKNOWLEDGED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    AcknowledgeJournalExportHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID handle(AcknowledgeJournalExport command, ScopeContext scope) {
        if (command == null || command.exportId() == null) {
            throw new ProblemException("request.invalid");
        }

        if (scope == null
                || scope.userId() == null
                || scope.policyClass() != PolicyClass.OWN
                || scope.entityId() == null
                || scope.locationId() != null) {
            throw new ProblemException("m9.journal.entity_required");
        }

        UUID exportId = command.exportId();

        List<String> statuses = jdbc.query(
                """
                select status
                  from integration.journal_export
                 where export_id = ?
                 for update
                """,
                (rs, rowNum) -> rs.getString("status"),
                exportId);

        if (statuses.isEmpty()) {
            throw new ProblemException("m9.journal.not_found");
        }

        String status = statuses.get(0);

        // A repeated acknowledgement must not change the record or emit
        // another audit entry or domain event.
        if ("ACKNOWLEDGED".equals(status)) {
            return exportId;
        }

        if (!"GENERATED".equals(status)) {
            throw new ProblemException("m9.journal.not_generated", Map.of("status", status));
        }

        Instant now = clock.instant();

        int updated = jdbc.update(
                """
                update integration.journal_export
                   set status = 'ACKNOWLEDGED',
                       acknowledged_by = ?,
                       acknowledged_at = ?
                 where export_id = ?
                   and status = 'GENERATED'
                """,
                scope.userId().toString(),
                Timestamp.from(now),
                exportId);

        if (updated != 1) {
            throw new ProblemException("m9.journal.not_found");
        }

        audit.record(
                AUDIT_ACKNOWLEDGED,
                Subject.of("journal_export", exportId),
                Map.of("status", "GENERATED"),
                Map.of("status", "ACKNOWLEDGED", "acknowledgedAt", now.toString()),
                scope);

        events.publish(new JournalExportAcknowledged(exportId, scope.entityId(), now));

        return exportId;
    }
}
