package lk.coopfed.knoweb.m3pricing.internal.review;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The gazette review and the batch-MRP review of doc 23 section 4.3 (23A section 7.1), second
 * half (M3R-08, CR-19A-14). Every run: the triggers {@link ReviewConsumers} recorded and no run
 * has checked, read with the federation-wide view (CR-19A-7). For each, the lists in force today
 * whose line of the SKU in the same unit is above the threshold (M3R-04): the ceiling's unit for a
 * gazette, the SKU's base unit for a printed MRP. Each breach is raised by {@link
 * RaiseReviewHandler} in the OWN scope of the list's owner, one per owner and list kind (M3R-05);
 * then {@link CheckReviewTriggerHandler} marks the trigger checked in its own owner's scope. Each
 * command is a transaction of its own, so one failure does not hold the rest; a trigger whose
 * reviews could not all be raised stays unchecked and is tried again on the next run, where the
 * reviews already raised are skipped by their replay key.
 */
@Component
public class PricingReviewJob {

    private static final Logger log = LoggerFactory.getLogger(PricingReviewJob.class);

    private final ReviewTriggers triggers;
    private final CatalogueQueries catalogue;
    private final RaiseReviewHandler raise;
    private final CheckReviewTriggerHandler check;
    private final Clock clock;
    private final ZoneId businessZone;

    PricingReviewJob(
            ReviewTriggers triggers,
            CatalogueQueries catalogue,
            RaiseReviewHandler raise,
            CheckReviewTriggerHandler check,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone) {
        this.triggers = triggers;
        this.catalogue = catalogue;
        this.raise = raise;
        this.check = check;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
    }

    @ScheduledJob(
            name = "pricing-review",
            cron = "${coop-erp.m3.pricing-review-cron:15 * * * * *}",
            lockTimeout = "PT15M",
            maxRuntime = "PT10M")
    public int raiseReviews(JobExecution execution) {
        ScopeContext view = execution.federationView();
        LocalDate today = LocalDate.ofInstant(clock.instant(), businessZone);

        int raised = 0;
        for (ReviewTriggers.Trigger trigger : triggers.unchecked(view)) {
            try {
                String unit = unitOf(trigger, view);
                int count = 0;
                boolean complete = true;
                for (ReviewTriggers.Breach breach : triggers.breaches(trigger, unit, today, view)) {
                    try {
                        raise.handle(review(trigger, breach), execution.ownScopeOf(breach.ownerEntityId()));
                        count++;
                    } catch (ProblemException already) {
                        if (!"m3.review.already_raised".equals(already.messageId())) {
                            complete = false;
                            log.error("A review of trigger {} could not be raised", trigger.triggerId(), already);
                        }
                    } catch (RuntimeException e) {
                        complete = false;
                        log.error("A review of trigger {} could not be raised", trigger.triggerId(), e);
                    }
                }
                if (complete) {
                    check.handle(
                            new CheckReviewTrigger(trigger.triggerId(), count),
                            execution.ownScopeOf(trigger.ownerEntityId()));
                }
                raised += count;
            } catch (ProblemException overtaken) {
                if ("m3.review.trigger_checked".equals(overtaken.messageId())) {
                    log.debug("Trigger {} skipped: {}", trigger.triggerId(), overtaken.messageId());
                } else {
                    log.error("Trigger {} could not be checked", trigger.triggerId(), overtaken);
                }
            } catch (RuntimeException e) {
                log.error("Trigger {} could not be checked", trigger.triggerId(), e);
            }
        }
        return raised;
    }

    /** The unit the threshold is in: the ceiling's, or the SKU's base unit for a printed MRP. */
    private String unitOf(ReviewTriggers.Trigger trigger, ScopeContext view) {
        if (trigger.uomCode() != null) {
            return trigger.uomCode();
        }
        Optional<SkuView> sku = catalogue.getSku(trigger.skuId(), view);
        return sku.map(SkuView::baseUomCode).orElse(null);
    }

    private static RaiseReview review(ReviewTriggers.Trigger trigger, ReviewTriggers.Breach breach) {
        String key = trigger.sourceId() + ":" + breach.listKind();
        Map<String, Object> details = new LinkedHashMap<>();
        if (ReviewTriggers.BATCH_MRP.equals(trigger.kind())) {
            details.put("batchId", trigger.sourceId());
            details.put("batchNo", trigger.sourceRef());
            details.put("printedMrp", trigger.thresholdPrice());
        } else {
            details.put("controlPriceId", trigger.sourceId());
            details.put("gazetteReference", trigger.sourceRef());
            details.put("ceilingPrice", trigger.thresholdPrice());
        }
        details.put("listKind", breach.listKind());
        details.put("uomCode", breach.uomCode());
        details.put("listPrice", breach.listPrice());
        String kind = ReviewTriggers.BATCH_MRP.equals(trigger.kind())
                ? PricingReviews.BATCH_MRP_BELOW_LIST
                : PricingReviews.LIST_ABOVE_CEILING_AT_GAZETTE;
        UUID sku = trigger.skuId();
        return new RaiseReview(kind, sku, key, trigger.sourceRef(), details);
    }
}
