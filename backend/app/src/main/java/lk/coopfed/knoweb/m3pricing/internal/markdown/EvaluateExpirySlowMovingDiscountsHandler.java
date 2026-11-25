package lk.coopfed.knoweb.m3pricing.internal.markdown;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m3pricing.api.EvaluateExpirySlowMovingDiscounts;
import lk.coopfed.knoweb.m3pricing.api.ExpirySlowMovingDiscountsEvaluated;
import lk.coopfed.knoweb.m3pricing.api.ExpirySlowMovingEvaluationResult;
import lk.coopfed.knoweb.m3pricing.query.ExpirySlowMovingDiscountView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Command handler to evaluate and activate near-expiry and slow-moving discount markdowns (PGL-M1-07).
 * Consumes authoritative inventory candidates, applies configured policies, audits the evaluation,
 * and publishes domain event pricing.expiry_slow_moving_discounts_evaluated.v1.
 */
@Service
@CommandHandler(permission = "prc.rule.activate")
public class EvaluateExpirySlowMovingDiscountsHandler
        implements Handles<EvaluateExpirySlowMovingDiscounts, ExpirySlowMovingEvaluationResult> {

    public static final String AUDIT_EVALUATED = "EXPIRY_SLOW_MOVING_DISCOUNTS_EVALUATED";

    private final ExpirySlowMovingDiscountCalculator calculator;
    private final PartyQueries party;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;
    private final ZoneId businessZone;

    public EvaluateExpirySlowMovingDiscountsHandler(
            ExpirySlowMovingDiscountCalculator calculator,
            PartyQueries party,
            AuditFacade audit,
            EventPublisher events,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone) {
        this.calculator = calculator;
        this.party = party;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
    }

    @Override
    @Transactional
    public ExpirySlowMovingEvaluationResult handle(EvaluateExpirySlowMovingDiscounts command, ScopeContext scope) {
        if (command == null || command.locationId() == null) {
            throw new ProblemException("request.invalid");
        }
        requireOwnerScope(scope);

        LocationView location = party.getLocation(command.locationId(), scope)
                .filter(found -> found.ownerEntityId().equals(scope.entityId()))
                .orElseThrow(() -> new ProblemException("m3.label.location_not_owned"));

        if (scope.locationId() != null && !scope.locationId().equals(command.locationId())) {
            throw new ProblemException("m3.label.location_not_owned");
        }

        List<ExpirySlowMovingDiscountView> discounts = calculator.calculate(command.locationId(), null, null, scope);

        int totalEvaluated = discounts.size();
        int nearExpiryCount = (int) discounts.stream()
                .filter(d -> "NEAR_EXPIRY".equalsIgnoreCase(d.reason()))
                .count();
        int slowMovingCount = (int) discounts.stream()
                .filter(d -> "SLOW_MOVING".equalsIgnoreCase(d.reason()))
                .count();
        int markdownsActive = discounts.size();
        Instant evaluatedAt = clock.instant();
        UUID evaluationId = Ids.next();

        audit.record(
                AUDIT_EVALUATED,
                Subject.of("location", command.locationId()),
                null,
                Map.of(
                        "evaluationId", evaluationId.toString(),
                        "locationId", command.locationId().toString(),
                        "totalEvaluated", totalEvaluated,
                        "markdownsActive", markdownsActive,
                        "nearExpiryCount", nearExpiryCount,
                        "slowMovingCount", slowMovingCount),
                scope);

        events.publish(new ExpirySlowMovingDiscountsEvaluated(
                evaluationId,
                scope.entityId(),
                command.locationId(),
                totalEvaluated,
                markdownsActive,
                nearExpiryCount,
                slowMovingCount,
                evaluatedAt));

        return new ExpirySlowMovingEvaluationResult(
                command.locationId(), totalEvaluated, markdownsActive, nearExpiryCount, slowMovingCount, evaluatedAt);
    }

    private static void requireOwnerScope(ScopeContext scope) {
        if (scope == null
                || !scope.hasActiveScope()
                || scope.entityId() == null
                || scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("scope.invalid");
        }
    }
}
