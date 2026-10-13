package lk.coopfed.knoweb.m3pricing.internal.review;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.AuditReviewReader;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m3pricing.api.ReviewRaised;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RaiseReview (doc 23 section 4.3; 23A section 7.1), sent by {@link PricingReviewJob} in the OWN
 * scope of the list's owner. Guards: an OWN scope; a pricing review kind; no review of the kind
 * for the SKU with the same replay key in the owner's scope ({@code m3.review.already_raised}: a
 * replay of the job). Mutation: none besides the record itself, which is the REVIEW audit record
 * the exception queue and listReviews read. Event: pricing.review_raised.v1.
 *
 * <p>The job runs with no user, so the permission is not checked (as for M2's thumbnail job); it
 * is the owner's prc.pricelist.author, the work a review asks of the owner (a code the slice carries).
 */
@Service
@CommandHandler(permission = "prc.pricelist.author")
class RaiseReviewHandler implements Handles<RaiseReview, UUID> {

    private final AuditReviewReader reviews;
    private final AuditFacade audit;
    private final EventPublisher events;

    RaiseReviewHandler(AuditReviewReader reviews, AuditFacade audit, EventPublisher events) {
        this.reviews = reviews;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RaiseReview command, ScopeContext scope) {
        if (command == null
                || command.kind() == null
                || !PricingReviews.KINDS.contains(command.kind())
                || command.skuId() == null
                || command.reviewKey() == null) {
            throw new ProblemException("request.invalid");
        }
        if (scope == null
                || !scope.hasActiveScope()
                || scope.entityId() == null
                || scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("scope.invalid");
        }
        if (reviews.reviewExists(
                command.kind(), command.skuId(), PricingReviews.REVIEW_KEY, command.reviewKey(), scope)) {
            throw new ProblemException("m3.review.already_raised");
        }

        Map<String, Object> details = new LinkedHashMap<>();
        if (command.details() != null) {
            details.putAll(command.details());
        }
        details.put(PricingReviews.REVIEW_KEY, command.reviewKey());
        audit.record(command.kind(), Subject.of("sku", command.skuId()), null, details, scope);
        events.publish(new ReviewRaised(command.kind(), command.skuId(), scope.entityId(), command.reference()));
        return command.skuId();
    }
}
