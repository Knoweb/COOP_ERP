package lk.coopfed.knoweb.m3pricing.internal.review;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
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
import lk.coopfed.knoweb.m3pricing.api.ReviewCheckCompleted;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CheckReviewTrigger, sent by {@link PricingReviewJob} in the OWN scope of the trigger's owner
 * once every review it called for is raised. Guards: an OWN scope; the caller's own trigger
 * ({@code m3.review.trigger_not_found}), not yet checked ({@code m3.review.trigger_checked}:
 * another run got there first). Mutation: checked_at and the count. Audit PRICING_REVIEW_CHECKED;
 * event pricing.review_check_completed.v1.
 *
 * <p>The job runs with no user, so the permission is not checked; prc.pricelist.author as for
 * RaiseReview.
 */
@Service
@CommandHandler(permission = "prc.pricelist.author")
class CheckReviewTriggerHandler implements Handles<CheckReviewTrigger, UUID> {

    static final String AUDIT_CHECKED = "PRICING_REVIEW_CHECKED";

    private final ReviewTriggers triggers;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    CheckReviewTriggerHandler(
            ReviewTriggers triggers, JdbcTemplate jdbc, Clock clock, AuditFacade audit, EventPublisher events) {
        this.triggers = triggers;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(CheckReviewTrigger command, ScopeContext scope) {
        if (command == null || command.triggerId() == null || command.reviewsRaised() < 0) {
            throw new ProblemException("request.invalid");
        }
        if (scope == null
                || !scope.hasActiveScope()
                || scope.entityId() == null
                || scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("scope.invalid");
        }
        boolean checked = triggers.checkedForUpdate(command.triggerId())
                .orElseThrow(() -> new ProblemException("m3.review.trigger_not_found"));
        if (checked) {
            throw new ProblemException("m3.review.trigger_checked");
        }

        jdbc.update(
                "update pricing.review_trigger set checked_at = ?, reviews_raised = ? where trigger_id = ?",
                Timestamp.from(clock.instant()),
                command.reviewsRaised(),
                command.triggerId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("checked", true);
        after.put("reviewsRaised", command.reviewsRaised());
        audit.record(AUDIT_CHECKED, Subject.of("review_trigger", command.triggerId()), null, after, scope);
        events.publish(new ReviewCheckCompleted(command.triggerId(), scope.entityId(), command.reviewsRaised()));
        return command.triggerId();
    }
}
