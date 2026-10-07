package lk.coopfed.knoweb.m1party.internal.relationship;

import java.math.BigDecimal;
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
import lk.coopfed.knoweb.m1party.api.CreditLimitChanged;
import lk.coopfed.knoweb.m1party.api.RelationshipActivated;
import lk.coopfed.knoweb.m1party.api.TradePriceListCheck;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ActivateRelationship (21A section 6; doc 21 flow 6.2): the seller puts a DRAFT into force.
 * From here M4 accepts the buyer's orders under it. A draft with a credit limit passes the
 * limit's gate ({@link CreditLimitGate}) and publishes {@code credit_limit.changed.v1}, null to
 * the opening limit, audited as CREDIT_LIMIT_CHANGED (wave 2: CR-21A-7; M8 D6).
 */
@Service
@CommandHandler(permission = "prt.relationship.activate")
class ActivateRelationshipHandler implements Handles<ActivateRelationship, UUID> {

    static final String AUDIT_ACTIVATED = "RELATIONSHIP_ACTIVATED";

    private final RelationshipRepository repository;
    private final TradePriceListCheck priceLists;
    private final CreditLimitGate creditLimitGate;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    ActivateRelationshipHandler(
            RelationshipRepository repository,
            TradePriceListCheck priceLists,
            CreditLimitGate creditLimitGate,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events) {
        this.repository = repository;
        this.priceLists = priceLists;
        this.creditLimitGate = creditLimitGate;
        this.jdbc = jdbc;
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

        // 3b. a draft that carries a credit limit puts it into force: the limit's gate
        //     (CR-21A-7), whoever opened the draft.
        boolean withLimit = relationship.creditLimit() != null;
        if (withLimit) {
            creditLimitGate.require(scope);
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
        if (withLimit) {
            jdbc.update(
                    RelationshipRules.MARK_LIMIT_ANNOUNCED,
                    relationship.sellerEntityId(),
                    relationship.buyerEntityId(),
                    relationship.getId());
        }

        audit.record(
                AUDIT_ACTIVATED,
                Subject.of("relationship", relationship.getId()),
                before,
                relationship.auditState(),
                scope);
        if (withLimit) {
            audit.record(
                    AmendRelationshipTermsHandler.AUDIT_CREDIT_LIMIT_CHANGED,
                    Subject.of("relationship", relationship.getId()),
                    limitState(relationship.getId(), null),
                    limitState(relationship.getId(), relationship.creditLimit()),
                    scope);
        }

        events.publish(new RelationshipActivated(
                relationship.getId(),
                relationship.sellerEntityId(),
                relationship.buyerEntityId(),
                relationship.effectiveFrom(),
                relationship.effectiveTo(),
                relationship.terms().hash()));
        if (withLimit) {
            // The opening limit as an event (M8 D6): null to the limit, so a projection of the
            // current limit needs nothing but credit_limit.changed.v1.
            events.publish(new CreditLimitChanged(
                    relationship.getId(),
                    null,
                    null,
                    relationship.creditLimit(),
                    relationship.effectiveFrom(),
                    relationship.sellerEntityId(),
                    relationship.buyerEntityId()));
        }

        return relationship.getId();
    }

    static Map<String, Object> limitState(UUID relationshipId, BigDecimal creditLimit) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("relationshipId", relationshipId);
        state.put("creditLimit", creditLimit);
        return state;
    }
}
