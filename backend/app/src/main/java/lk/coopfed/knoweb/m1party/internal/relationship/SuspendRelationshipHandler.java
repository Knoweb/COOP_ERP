package lk.coopfed.knoweb.m1party.internal.relationship;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.RelationshipSuspended;
import lk.coopfed.knoweb.m1party.api.SuspendRelationship;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SuspendRelationship (21A section 6; doc 21 section 4.2): the seller stops new trading under
 * an ACTIVE relationship. Documents already open under it are unaffected (M4 reads the status
 * when a new order is created).
 *
 * <p>After an amendment a relationship is several ACTIVE rows, one per period of terms (doc 21
 * section 4.2, "effective-dated"). A suspension is of the relationship, not of one period: the
 * row named by the command and every other ACTIVE row of the pair that is in force today or
 * starts later are suspended together, so that trading does not come back the day the next row
 * starts (the review of M1-04). Rows that ended before today are history and stay as they are.
 * Each row suspended gets its own audit record and event, as it would have alone.
 */
@Service
@CommandHandler(permission = "prt.relationship.suspend", requiresMfa = true)
class SuspendRelationshipHandler implements Handles<SuspendRelationship, UUID> {

    static final String AUDIT_SUSPENDED = "RELATIONSHIP_SUSPENDED";

    private final RelationshipRepository repository;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    SuspendRelationshipHandler(
            RelationshipRepository repository, Clock clock, AuditFacade audit, EventPublisher events) {
        this.repository = repository;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(SuspendRelationship command, ScopeContext scope) {

        Relationship relationship =
                RelationshipRules.found(repository, command == null ? null : command.relationshipId());

        // 1. only the seller suspends; the row is then locked and re-read.
        relationship = RelationshipRules.lockedForSeller(repository, scope, relationship);

        // 2. status ACTIVE.
        if (!relationship.isActive()) {
            throw new ProblemException(
                    "m1.relationship.not_active",
                    Map.of("relationshipId", relationship.getId(), "status", relationship.status()));
        }

        // 3. a reason.
        RelationshipRules.requireReason(command.reasonCode());

        // The rows to suspend: the one named, and every other ACTIVE row of the pair in force
        // today or later, each locked so a concurrent amendment waits for this to commit. The
        // date is the UTC calendar day; a row that ended late yesterday is left alone either way.
        LocalDate today = LocalDate.now(clock);
        List<Relationship> rows = new ArrayList<>();
        rows.add(relationship);
        for (Relationship other : repository.activeOnOrAfterForUpdate(
                relationship.sellerEntityId(), relationship.buyerEntityId(), today)) {
            if (!other.getId().equals(relationship.getId())) {
                rows.add(other);
            }
        }

        Map<UUID, Map<String, Object>> before = new LinkedHashMap<>();
        for (Relationship row : rows) {
            before.put(row.getId(), row.auditState());
            row.suspend();
            repository.saveAndFlush(row);
        }

        String reason = RelationshipRules.auditReason(command.reasonCode(), command.reasonText());
        for (Relationship row : rows) {
            audit.record(
                    AUDIT_SUSPENDED,
                    Subject.of("relationship", row.getId()),
                    before.get(row.getId()),
                    row.auditState(),
                    scope,
                    reason);
        }

        for (Relationship row : rows) {
            events.publish(new RelationshipSuspended(
                    row.getId(),
                    row.sellerEntityId(),
                    row.buyerEntityId(),
                    row.effectiveFrom(),
                    row.effectiveTo(),
                    row.terms().hash()));
        }

        return relationship.getId();
    }
}
