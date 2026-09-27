package lk.coopfed.knoweb.m1party.internal.relationship;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.AmendRelationshipTerms;
import lk.coopfed.knoweb.m1party.api.CreditLimitChanged;
import lk.coopfed.knoweb.m1party.api.RelationshipAmended;
import lk.coopfed.knoweb.m1party.api.TradePriceListCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AmendRelationshipTerms (21A section 6.1; doc 21 flow 6.4). Effective-dated: the current row
 * is closed the day before the new terms start and a new ACTIVE row carries them. The old
 * row's terms are never edited; its closing date is the only thing that changes on it.
 *
 * <p>Only the latest row of the pair is amended. The dates (CR-21A-2, accepted 27 September
 * 2026):
 *
 * <ul>
 *   <li>the new terms start after that row's first day (21A section 6) and never before today,
 *       the calendar date in the business time zone: what LookupRelationship answers for a day
 *       that has begun never changes, so M4's orders, due dates and exposure of that day keep
 *       the terms they were accepted under (item 1);
 *   <li>a row whose first day is still to come is corrected by an amendment dated exactly on
 *       that first day: the row becomes REPLACED (never in force, kept as the record) and the
 *       new ACTIVE row takes its whole range (item 2).
 * </ul>
 *
 * <p>A change of the credit limit needs more than {@code prt.relationship.amend}: the caller
 * must also hold {@code bil.creditlimit.change} and have presented a second factor recently.
 * Both are guards here, not annotation attributes, because they apply only when the limit
 * changes; the interceptor cannot know that before the handler compares the terms.
 */
@Service
@CommandHandler(permission = "prt.relationship.amend")
class AmendRelationshipTermsHandler implements Handles<AmendRelationshipTerms, UUID> {

    static final String AUDIT_AMENDED = "RELATIONSHIP_AMENDED";
    static final String AUDIT_CREDIT_LIMIT_CHANGED = "CREDIT_LIMIT_CHANGED";

    /** Owned by M4, granted by M1 (21A section 3.3). */
    static final String CREDIT_LIMIT_PERMISSION = "bil.creditlimit.change";

    /** How recent the second factor must be for a limit change; the kernel's config setter uses ten minutes too. */
    static final String MFA_MAX_AGE = "m1.relationship.credit_limit_mfa_max_age";

    static final Duration DEFAULT_MFA_MAX_AGE = Duration.ofMinutes(10);

    private final RelationshipRepository repository;
    private final TradePriceListCheck priceLists;
    private final Optional<PermissionResolver> permissions;
    private final ConfigRegistry config;
    private final Clock clock;
    private final ZoneId businessZone;
    private final AuditFacade audit;
    private final EventPublisher events;

    AmendRelationshipTermsHandler(
            RelationshipRepository repository,
            TradePriceListCheck priceLists,
            Optional<PermissionResolver> permissions,
            ConfigRegistry config,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone,
            AuditFacade audit,
            EventPublisher events) {
        this.repository = repository;
        this.priceLists = priceLists;
        this.permissions = permissions;
        this.config = config;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(AmendRelationshipTerms command, ScopeContext scope) {

        Relationship current = RelationshipRules.found(repository, command == null ? null : command.relationshipId());

        // 1. the row is ACTIVE.
        requireActive(current);

        // 2. only the seller amends; the row is then locked and re-read, and is still ACTIVE
        //    (a suspension committed meanwhile is seen here, not written over).
        current = RelationshipRules.lockedForSeller(repository, scope, current);
        requireActive(current);

        // 3. the new terms start after the current row's first day, not before today, and while
        //    the current row still runs; or, for a row that has not started, exactly on its first
        //    day, which replaces it (CR-21A-2).
        if (command.effectiveFrom() == null) {
            throw new ProblemException("request.field.required", Map.of("field", "effectiveFrom"));
        }
        LocalDate today = RelationshipRules.businessToday(clock, businessZone);
        boolean replacesUnstartedRow = current.effectiveFrom().isAfter(today)
                && command.effectiveFrom().equals(current.effectiveFrom());
        if (!replacesUnstartedRow && !command.effectiveFrom().isAfter(current.effectiveFrom())) {
            throw new ProblemException(
                    "m1.relationship.effective_from_not_after",
                    Map.of("currentEffectiveFrom", current.effectiveFrom().toString()));
        }
        if (command.effectiveFrom().isBefore(today)) {
            throw new ProblemException(
                    "m1.relationship.effective_from_in_past",
                    Map.of("effectiveFrom", command.effectiveFrom().toString(), "today", today.toString()));
        }
        if (current.effectiveTo() != null && command.effectiveFrom().isAfter(current.effectiveTo())) {
            throw new ProblemException(
                    "m1.relationship.effective_from_after_end",
                    Map.of("currentEffectiveTo", current.effectiveTo().toString()));
        }

        // 3b. the row is the latest of its pair: an earlier row's amendment would stop the day
        //     the later row starts (RelationshipRules.requireLatest says why).
        RelationshipRules.requireLatest(repository, current);

        Relationship.Terms before = current.terms();
        Relationship.Terms after = amended(before, command);

        // 3c. a new price list passes the same M3 check as at activation (21A section 6,
        //     ActivateRelationship: "price list belongs to seller and is published"); the check
        //     runs only when the list changes, since the current list already passed it.
        if (command.priceListId() != null && !command.priceListId().equals(before.priceListId())) {
            Optional<String> refusal = priceLists.refusal(command.priceListId(), current.sellerEntityId(), scope);
            if (refusal.isPresent()) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("priceListId", command.priceListId());
                params.put("reason", refusal.get());
                throw new ProblemException("m1.relationship.price_list_refused", params);
            }
        }

        // 4. a credit-limit change: the extra permission and a fresh second factor.
        boolean limitChange = command.creditLimit() != null && !before.sameCreditLimitAs(command.creditLimit());
        if (limitChange) {
            requireCreditLimitPermission(scope);
            requireFreshMfa(scope);
        }

        // 5. a reason.
        RelationshipRules.requireReason(command.reasonCode());

        // 6. something changes; an amendment that changes nothing would only add a row.
        if (after.sameAs(before)) {
            throw new ProblemException("m1.relationship.terms_unchanged");
        }

        // 7. no other ACTIVE row of the pair meets the new row's range (pre-check; the
        //    exclusion constraint is the backstop). The current row is closed first, so it is
        //    not "another" row.
        RelationshipRules.requireNoOverlap(
                repository,
                current.sellerEntityId(),
                current.buyerEntityId(),
                command.effectiveFrom(),
                current.effectiveTo(),
                current.getId());

        Map<String, Object> currentBefore = current.auditState();

        // mutation: close (or replace) the current row, then insert the next. In this order,
        // because the exclusion constraint is checked row by row, not at commit. A replaced row
        // keeps its range; the next row takes the same one (amendedFrom copies the end).
        Relationship next = current.amendedFrom(Ids.next(), after, command.effectiveFrom());
        if (replacesUnstartedRow) {
            current.replace();
        } else {
            current.closeBefore(command.effectiveFrom());
        }
        try {
            repository.saveAndFlush(current);
            repository.saveAndFlush(next);
        } catch (DataIntegrityViolationException ex) {
            throw RelationshipRules.translate(ex, next);
        }

        String reason = RelationshipRules.auditReason(command.reasonCode(), command.reasonText());

        // before: the current row as it stood; after: the row that replaces it from effectiveFrom.
        audit.record(
                AUDIT_AMENDED,
                Subject.of("relationship", next.getId()),
                currentBefore,
                next.auditState(),
                scope,
                reason);

        if (limitChange) {
            audit.record(
                    AUDIT_CREDIT_LIMIT_CHANGED,
                    Subject.of("relationship", next.getId()),
                    limitState(current.getId(), before.creditLimit()),
                    limitState(next.getId(), after.creditLimit()),
                    scope,
                    reason);
        }

        events.publish(new RelationshipAmended(
                next.getId(),
                current.getId(),
                next.sellerEntityId(),
                next.buyerEntityId(),
                next.effectiveFrom(),
                next.effectiveTo(),
                after.hash()));

        if (limitChange) {
            events.publish(new CreditLimitChanged(
                    next.getId(), current.getId(), before.creditLimit(), after.creditLimit(), next.effectiveFrom()));
        }

        return next.getId();
    }

    private static void requireActive(Relationship current) {
        if (!current.isActive()) {
            throw new ProblemException(
                    "m1.relationship.not_active",
                    Map.of("relationshipId", current.getId(), "status", current.status()));
        }
    }

    /**
     * Until K-03b gives the kernel a permission resolver, nothing on the server resolves a
     * permission and this check cannot be made: with no resolver it is skipped, as the
     * interceptor skips the handler's own permission today. The day a resolver bean exists,
     * the check runs with no change here.
     */
    private void requireCreditLimitPermission(ScopeContext scope) {
        permissions.ifPresent(resolver -> {
            if (!resolver.allows(scope, CREDIT_LIMIT_PERMISSION)) {
                throw new ProblemException(
                        "m1.relationship.credit_limit_permission_required",
                        Map.of("permission", CREDIT_LIMIT_PERMISSION));
            }
        });
    }

    /** The step-up of doc 21 flow 6.4: a second factor presented within the configured age. */
    private void requireFreshMfa(ScopeContext scope) {
        Duration maxAge = config.getDuration(MFA_MAX_AGE, scope, DEFAULT_MFA_MAX_AGE);
        Instant freshEnough = clock.instant().minus(maxAge);
        if (scope.mfaAt() == null || scope.mfaAt().isBefore(freshEnough)) {
            throw new ProblemException("mfa.required", Map.of("permission", CREDIT_LIMIT_PERMISSION));
        }
    }

    private static Relationship.Terms amended(Relationship.Terms current, AmendRelationshipTerms command) {
        return new Relationship.Terms(
                command.priceListId() == null ? current.priceListId() : command.priceListId(),
                command.creditLimit() == null ? current.creditLimit() : command.creditLimit(),
                command.paymentTermsDays() == null
                        ? current.paymentTermsDays()
                        : Short.valueOf(command.paymentTermsDays().shortValue()),
                command.discrepancyWindowDays() == null
                        ? current.discrepancyWindowDays()
                        : command.discrepancyWindowDays().shortValue(),
                command.orderLockHoursBeforeEta() == null
                        ? current.orderLockHoursBeforeEta()
                        : command.orderLockHoursBeforeEta().shortValue(),
                command.allocationRule() == null
                        ? current.allocationRule()
                        : OpenTradingRelationshipHandler.allocationRule(command.allocationRule()));
    }

    private static Map<String, Object> limitState(UUID relationshipId, BigDecimal creditLimit) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("relationshipId", relationshipId);
        state.put("creditLimit", creditLimit);
        return state;
    }
}
