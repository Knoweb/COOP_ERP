package lk.coopfed.knoweb.m1party.internal.relationship;

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
 */
@Service
@CommandHandler(permission = "prt.relationship.suspend", requiresMfa = true)
class SuspendRelationshipHandler implements Handles<SuspendRelationship, UUID> {

    static final String AUDIT_SUSPENDED = "RELATIONSHIP_SUSPENDED";

    private final RelationshipRepository repository;
    private final AuditFacade audit;
    private final EventPublisher events;

    SuspendRelationshipHandler(RelationshipRepository repository, AuditFacade audit, EventPublisher events) {
        this.repository = repository;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(SuspendRelationship command, ScopeContext scope) {

        Relationship relationship =
                RelationshipRules.found(repository, command == null ? null : command.relationshipId());

        // 1. only the seller suspends.
        RelationshipRules.requireSeller(scope, relationship);

        // 2. status ACTIVE.
        if (!relationship.isActive()) {
            throw new ProblemException(
                    "m1.relationship.not_active",
                    Map.of("relationshipId", relationship.getId(), "status", relationship.status()));
        }

        // 3. a reason.
        RelationshipRules.requireReason(command.reasonCode());

        Map<String, Object> before = relationship.auditState();

        relationship.suspend();
        repository.saveAndFlush(relationship);

        audit.record(
                AUDIT_SUSPENDED,
                Subject.of("relationship", relationship.getId()),
                before,
                relationship.auditState(),
                scope,
                RelationshipRules.auditReason(command.reasonCode(), command.reasonText()));

        events.publish(new RelationshipSuspended(
                relationship.getId(),
                relationship.sellerEntityId(),
                relationship.buyerEntityId(),
                relationship.effectiveFrom(),
                relationship.effectiveTo(),
                relationship.terms().hash()));

        return relationship.getId();
    }
}
