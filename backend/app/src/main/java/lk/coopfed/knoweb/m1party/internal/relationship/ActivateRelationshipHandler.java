package lk.coopfed.knoweb.m1party.internal.relationship;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.ActivateRelationship;
import lk.coopfed.knoweb.m1party.api.RelationshipActivated;
import lk.coopfed.knoweb.m1party.api.TradePriceListCheck;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ActivateRelationship (21A section 6; doc 21 flow 6.2): the seller puts a DRAFT into force.
 * From here M4 accepts the buyer's orders under it.
 */
@Service
@CommandHandler(permission = "prt.relationship.activate")
class ActivateRelationshipHandler implements Handles<ActivateRelationship, UUID> {

    static final String AUDIT_ACTIVATED = "RELATIONSHIP_ACTIVATED";

    private final RelationshipRepository repository;
    private final TradePriceListCheck priceLists;
    private final AuditFacade audit;
    private final EventPublisher events;

    ActivateRelationshipHandler(
            RelationshipRepository repository,
            TradePriceListCheck priceLists,
            AuditFacade audit,
            EventPublisher events) {
        this.repository = repository;
        this.priceLists = priceLists;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ActivateRelationship command, ScopeContext scope) {

        Relationship relationship =
                RelationshipRules.found(repository, command == null ? null : command.relationshipId());

        // 1. only the seller activates; the row is then locked and re-read.
        relationship = RelationshipRules.lockedForSeller(repository, scope, relationship);

        // 2. status DRAFT.
        if (!relationship.isDraft()) {
            throw new ProblemException(
                    "m1.relationship.not_draft",
                    Map.of("relationshipId", relationship.getId(), "status", relationship.status()));
        }

        // 3. terms complete: a price list to trade on and the days to pay (doc 21 section 4.2).
        if (relationship.priceListId() == null) {
            throw new ProblemException("m1.relationship.price_list_required");
        }
        if (relationship.paymentTermsDays() == null) {
            throw new ProblemException("m1.relationship.payment_terms_required");
        }

        // 4. M3: the list belongs to the seller and is published (stubbed until M3 answers).
        Optional<String> refusal = priceLists.refusal(relationship.priceListId(), relationship.sellerEntityId(), scope);
        if (refusal.isPresent()) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("priceListId", relationship.priceListId());
            params.put("reason", refusal.get());
            throw new ProblemException("m1.relationship.price_list_refused", params);
        }

        // 5. no other ACTIVE row of the pair overlaps (pre-check; the constraint is the backstop).
        RelationshipRules.requireNoOverlap(
                repository,
                relationship.sellerEntityId(),
                relationship.buyerEntityId(),
                relationship.effectiveFrom(),
                relationship.effectiveTo(),
                relationship.getId());

        Map<String, Object> before = relationship.auditState();

        relationship.activate();
        try {
            repository.saveAndFlush(relationship);
        } catch (DataIntegrityViolationException ex) {
            throw RelationshipRules.translate(ex, relationship);
        }

        audit.record(
                AUDIT_ACTIVATED,
                Subject.of("relationship", relationship.getId()),
                before,
                relationship.auditState(),
                scope);

        events.publish(new RelationshipActivated(
                relationship.getId(),
                relationship.sellerEntityId(),
                relationship.buyerEntityId(),
                relationship.effectiveFrom(),
                relationship.effectiveTo(),
                relationship.terms().hash()));

        return relationship.getId();
    }
}
