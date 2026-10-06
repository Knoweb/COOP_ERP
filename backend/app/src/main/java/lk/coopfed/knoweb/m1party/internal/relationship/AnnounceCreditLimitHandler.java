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
import lk.coopfed.knoweb.m1party.api.AnnounceCreditLimit;
import lk.coopfed.knoweb.m1party.api.CreditLimitChanged;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The opening limit of a relationship activated before activation published it, published once
 * (wave 2, CR-21A-7; M8 D6: "existing relationships are seeded by a one-off audited M1
 * publication"). Sent by {@link CreditLimitBackfillJob} in the seller's own scope. Guards: the
 * seller ({@code m1.relationship.not_seller}); ACTIVE ({@code m1.relationship.not_active}); a
 * limit to announce ({@code m1.relationship.credit_limit_none}); the pair not announced yet
 * ({@code m1.relationship.credit_limit_announced}).
 *
 * <p>No term changes and no credit-limit gate applies: the limit was set, and audited, when the
 * relationship was opened. The audit record says what was announced, with the reason
 * {@value #REASON}. The activation permission, because this is what activation does now; no
 * request carries the command, so the kernel checks no permission on it.
 */
@Service
@CommandHandler(permission = "prt.relationship.activate")
class AnnounceCreditLimitHandler implements Handles<AnnounceCreditLimit, UUID> {

    static final String REASON = "OPENING_LIMIT_ANNOUNCED";

    private final RelationshipRepository repository;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    AnnounceCreditLimitHandler(
            RelationshipRepository repository, JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(AnnounceCreditLimit command, ScopeContext scope) {

        Relationship relationship =
                RelationshipRules.found(repository, command == null ? null : command.relationshipId());
        relationship = RelationshipRules.lockedForSeller(repository, scope, relationship);

        if (!relationship.isActive()) {
            throw new ProblemException(
                    "m1.relationship.not_active",
                    Map.of("relationshipId", relationship.getId(), "status", relationship.status()));
        }
        if (relationship.creditLimit() == null) {
            throw new ProblemException(
                    "m1.relationship.credit_limit_none", Map.of("relationshipId", relationship.getId()));
        }
        if (announced(relationship)) {
            throw new ProblemException(
                    "m1.relationship.credit_limit_announced", Map.of("relationshipId", relationship.getId()));
        }

        jdbc.update(
                RelationshipRules.MARK_LIMIT_ANNOUNCED,
                relationship.sellerEntityId(),
                relationship.buyerEntityId(),
                relationship.getId());

        audit.record(
                AmendRelationshipTermsHandler.AUDIT_CREDIT_LIMIT_CHANGED,
                Subject.of("relationship", relationship.getId()),
                ActivateRelationshipHandler.limitState(relationship.getId(), null),
                ActivateRelationshipHandler.limitState(relationship.getId(), relationship.creditLimit()),
                scope,
                REASON);

        events.publish(new CreditLimitChanged(
                relationship.getId(),
                null,
                null,
                relationship.creditLimit(),
                relationship.effectiveFrom(),
                relationship.sellerEntityId(),
                relationship.buyerEntityId()));

        return relationship.getId();
    }

    private boolean announced(Relationship relationship) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                select exists (select 1 from security.credit_limit_announcement
                                where owner_entity_id = ? and buyer_entity_id = ?)
                """,
                Boolean.class,
                relationship.sellerEntityId(),
                relationship.buyerEntityId()));
    }
}
