package lk.coopfed.knoweb.m2catalogue.internal.steward;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.DuplicateSuspectResolved;
import lk.coopfed.knoweb.m2catalogue.api.ResolveDuplicateSuspect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles resolution of duplicate suspect pairs (CAT-01, 22A section 6.2).
 */
@Service
@CommandHandler(permission = "cat.sku.create_local")
public class ResolveDuplicateSuspectHandler implements Handles<ResolveDuplicateSuspect, Void> {

    public static final String AUDIT_SUSPECT_RESOLVED = "SUSPECT_RESOLVED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    public ResolveDuplicateSuspectHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Void handle(ResolveDuplicateSuspect command, ScopeContext scope) {
        if (scope == null || scope.entityId() == null) {
            throw new ProblemException("m2.sku.scope_required");
        }
        if (command == null || command.suspectId() == null) {
            throw new ProblemException("request.invalid");
        }

        String decision = command.decision() != null ? command.decision().trim().toUpperCase() : "";
        if (!ResolveDuplicateSuspect.MERGED.equals(decision) && !ResolveDuplicateSuspect.DISMISSED.equals(decision)) {
            throw new ProblemException("m2.suspect.decision_invalid");
        }

        Instant now = clock.instant();
        int updated = jdbc.update(
                """
                UPDATE catalogue.duplicate_suspect
                SET status = ?, decided_by = ?, decided_at = ?
                WHERE suspect_id = ? AND owner_entity_id = ? AND status = 'OPEN'
                """,
                decision,
                scope.userId(),
                Timestamp.from(now),
                command.suspectId(),
                scope.entityId());

        if (updated == 0) {
            throw new ProblemException("m2.suspect.not_found_or_closed");
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("suspectId", command.suspectId());
        summary.put("decision", decision);
        summary.put("decidedBy", scope.userId());
        summary.put("canonicalSkuId", command.canonicalSkuId());

        audit.record(
                AUDIT_SUSPECT_RESOLVED, Subject.of("duplicate_suspect", command.suspectId()), null, summary, scope);

        events.publish(new DuplicateSuspectResolved(command.suspectId(), decision, scope.userId(), scope.entityId()));

        return null;
    }
}
