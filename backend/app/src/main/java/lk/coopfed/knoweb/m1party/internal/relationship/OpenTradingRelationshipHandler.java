package lk.coopfed.knoweb.m1party.internal.relationship;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.OpenTradingRelationship;
import lk.coopfed.knoweb.m1party.api.RelationshipOpened;
import lk.coopfed.knoweb.m1party.internal.relationship.TradingStanding.Standing;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OpenTradingRelationship (21A section 6; doc 21 sections 3.2 and 4.2): the seller writes the
 * terms it offers the buyer, in DRAFT. Nothing trades under a DRAFT; activation does that.
 */
@Service
@CommandHandler(permission = "prt.relationship.open")
class OpenTradingRelationshipHandler implements Handles<OpenTradingRelationship, UUID> {

    static final String AUDIT_OPENED = "RELATIONSHIP_OPENED";

    /** The configuration item of doc 21 DR-3: may the Federation sell to a society directly. */
    static final String FEDERATION_DIRECT = "trade.federation_direct.enabled";

    private final RelationshipRepository repository;
    private final TradingStanding standing;
    private final ConfigRegistry config;
    private final AuditFacade audit;
    private final EventPublisher events;

    OpenTradingRelationshipHandler(
            RelationshipRepository repository,
            TradingStanding standing,
            ConfigRegistry config,
            AuditFacade audit,
            EventPublisher events) {
        this.repository = repository;
        this.standing = standing;
        this.config = config;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(OpenTradingRelationship command, ScopeContext scope) {

        // 1. seller = the caller's entity, acting entity-wide in its own scope.
        UUID seller = RelationshipRules.requireOwnEntityScope(scope);

        if (command == null || command.buyerEntityId() == null) {
            throw new ProblemException("request.field.required", Map.of("field", "buyerEntityId"));
        }
        if (command.effectiveFrom() == null) {
            throw new ProblemException("request.field.required", Map.of("field", "effectiveFrom"));
        }
        UUID buyer = command.buyerEntityId();

        // 2. seller is not the buyer: a movement inside one entity is an M5 document, never a relationship.
        if (seller.equals(buyer)) {
            throw new ProblemException("m1.relationship.self");
        }

        // 3. both parties ACTIVE or ONBOARDING.
        Standing sellerStanding =
                standing.of(seller).orElseThrow(() -> new ProblemException("m1.relationship.seller_scope_required"));
        Standing buyerStanding = standing.of(buyer)
                .orElseThrow(
                        () -> new ProblemException("m1.relationship.buyer_not_found", Map.of("buyerEntityId", buyer)));
        requireTrading(seller, sellerStanding);
        requireTrading(buyer, buyerStanding);

        // 4. the tier rule, or the configuration flag for the Federation selling to a society.
        requireTier(sellerStanding.entityType(), buyerStanding.entityType(), scope);

        // 5. the range and the terms make sense.
        if (command.effectiveTo() != null && command.effectiveTo().isBefore(command.effectiveFrom())) {
            throw new ProblemException("m1.relationship.effective_range_invalid");
        }
        Relationship.Terms terms = terms(command);

        // 6. no ACTIVE row of the pair overlaps (pre-check; the exclusion constraint is the backstop).
        UUID id = Ids.next();
        RelationshipRules.requireNoOverlap(
                repository, seller, buyer, command.effectiveFrom(), command.effectiveTo(), id);

        // mutation: a DRAFT row.
        Relationship relationship =
                Relationship.open(id, seller, buyer, terms, command.effectiveFrom(), command.effectiveTo());
        try {
            repository.saveAndFlush(relationship);
        } catch (DataIntegrityViolationException ex) {
            throw RelationshipRules.translate(ex, relationship);
        }

        audit.record(
                AUDIT_OPENED, Subject.of("relationship", relationship.getId()), null, relationship.auditState(), scope);

        events.publish(new RelationshipOpened(
                relationship.getId(),
                seller,
                buyer,
                relationship.effectiveFrom(),
                relationship.effectiveTo(),
                terms.hash()));

        return relationship.getId();
    }

    private static void requireTrading(UUID entityId, Standing party) {
        if (!party.canTrade()) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("entityId", entityId);
            params.put("status", party.status());
            throw new ProblemException("m1.relationship.party_not_trading", params);
        }
    }

    /**
     * Phase 1 permits Federation to distributor and distributor to society (doc 21 section 3.2).
     * Federation to society is not blocked by the schema, only by the flag, so opening that
     * channel later is data, not code. assumes doc 10 F-03 (flag present, default off).
     */
    private void requireTier(String sellerType, String buyerType, ScopeContext scope) {
        boolean allowed = ("FEDERATION".equals(sellerType) && "DISTRIBUTOR".equals(buyerType))
                || ("DISTRIBUTOR".equals(sellerType) && "MPCS".equals(buyerType))
                || ("FEDERATION".equals(sellerType)
                        && "MPCS".equals(buyerType)
                        && config.getBoolean(FEDERATION_DIRECT, scope, false));
        if (!allowed) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("sellerType", sellerType);
            params.put("buyerType", buyerType);
            throw new ProblemException("m1.relationship.tier_not_allowed", params);
        }
    }

    private static Relationship.Terms terms(OpenTradingRelationship command) {
        return new Relationship.Terms(
                command.priceListId(),
                command.creditLimit(),
                command.paymentTermsDays() == null
                        ? null
                        : command.paymentTermsDays().shortValue(),
                command.discrepancyWindowDays() == null
                        ? Relationship.DEFAULT_DISCREPANCY_WINDOW_DAYS
                        : command.discrepancyWindowDays().shortValue(),
                command.orderLockHoursBeforeEta() == null
                        ? Relationship.DEFAULT_ORDER_LOCK_HOURS
                        : command.orderLockHoursBeforeEta().shortValue(),
                allocationRule(command.allocationRule()));
    }

    static String allocationRule(String value) {
        if (value == null || value.isBlank()) {
            return Relationship.DEFAULT_ALLOCATION_RULE;
        }
        String rule = value.strip().toUpperCase(Locale.ROOT);
        if (!RelationshipRules.ALLOCATION_RULES.contains(rule)) {
            throw new ProblemException("m1.relationship.allocation_rule_invalid", Map.of("allocationRule", value));
        }
        return rule;
    }
}
