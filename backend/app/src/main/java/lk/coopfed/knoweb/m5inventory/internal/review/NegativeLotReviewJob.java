package lk.coopfed.knoweb.m5inventory.internal.review;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.internal.control.BusinessDay;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Nightly scheduled job that escalates unacknowledged oversold stock lots after 7 days
 * (doc 25 sections 3.2, 10; doc 25A section 7.2; ticket M5-06b / INV-04).
 *
 * <p>When a lot has been negative for at least {@code inventory.negative_lot_escalation_days}
 * (default 7 business days) without an acknowledgement, this job records an audited {@code REVIEW}
 * of type {@code STOCK_LOT_NEGATIVE_ESCALATED} and publishes {@code lot.negative.escalated.v1}.
 * It makes zero writes to stock balances or movements.
 */
@Component
public class NegativeLotReviewJob {

    private static final Logger log = LoggerFactory.getLogger(NegativeLotReviewJob.class);

    public static final String AUDIT_STOCK_LOT_NEGATIVE_ESCALATED = "STOCK_LOT_NEGATIVE_ESCALATED";

    private final NegativeLotReviewStore store;
    private final NegativeLotEscalationReporter reporter;
    private final ControlPolicy policy;
    private final BusinessDay businessDay;

    public NegativeLotReviewJob(
            NegativeLotReviewStore store,
            NegativeLotEscalationReporter reporter,
            ControlPolicy policy,
            BusinessDay businessDay) {
        this.store = store;
        this.reporter = reporter;
        this.policy = policy;
        this.businessDay = businessDay;
    }

    /**
     * Nightly scan across all locations in the federation (02:00 Colombo time).
     */
    @ScheduledJob(
            name = "inventory-negative-lot-review",
            cron = "${coop-erp.m5.negative-lot-review-cron:0 0 2 * * *}",
            lockTimeout = "PT15M",
            maxRuntime = "PT10M")
    public int review(JobExecution execution) {
        ScopeContext view = execution.federationView();
        List<NegativeLotReviewStore.CandidateLot> candidates = store.findCandidateLots(view);

        int escalated = 0;
        LocalDate today = businessDay.today();

        for (NegativeLotReviewStore.CandidateLot candidate : candidates) {
            ScopeContext ownScope = execution.ownScopeOf(candidate.ownerEntityId());
            int thresholdDays = policy.negativeLotEscalationDays(ownScope);

            LocalDate negativeDate = businessDay.dateOf(candidate.negativeSince());
            long daysNegative = ChronoUnit.DAYS.between(negativeDate, today);

            if (daysNegative >= thresholdDays) {
                try {
                    if (reporter.reportEscalation(candidate, thresholdDays, ownScope)) {
                        escalated++;
                    }
                } catch (RuntimeException e) {
                    log.error("Failed to escalate negative lot {}", candidate.stockLotId(), e);
                }
            }
        }

        execution.itemsProcessed(escalated);
        return escalated;
    }

    /**
     * Review negative lots for a single location under its entity's OWN scope.
     *
     * @param locationId location to check
     * @param scope caller's OWN scope
     * @return number of newly escalated lots
     */
    public int reviewLocation(UUID locationId, ScopeContext scope) {
        List<NegativeLotReviewStore.CandidateLot> candidates = store.findCandidateLots(locationId, scope);
        int thresholdDays = policy.negativeLotEscalationDays(scope);
        int escalated = 0;
        LocalDate today = businessDay.today();

        for (NegativeLotReviewStore.CandidateLot candidate : candidates) {
            LocalDate negativeDate = businessDay.dateOf(candidate.negativeSince());
            long daysNegative = ChronoUnit.DAYS.between(negativeDate, today);

            if (daysNegative >= thresholdDays) {
                if (reporter.reportEscalation(candidate, thresholdDays, scope)) {
                    escalated++;
                }
            }
        }
        return escalated;
    }
}
