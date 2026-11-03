package lk.coopfed.knoweb.m5inventory.internal.expiry;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.api.ExpiryAlertScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Scheduled nightly sweep evaluating near-expiry and slow-moving stock alerts across the federation (PGL-M2-07).
 */
@Component
public class ExpiryAlertReviewJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiryAlertReviewJob.class);

    private final ExpiryTaskStore taskStore;
    private final EvaluateExpiryAlertsHandler evaluationHandler;

    public ExpiryAlertReviewJob(ExpiryTaskStore taskStore, EvaluateExpiryAlertsHandler evaluationHandler) {
        this.taskStore = taskStore;
        this.evaluationHandler = evaluationHandler;
    }

    @ScheduledJob(
            name = "inventory-expiry-alert-review",
            cron = "${coop-erp.m5.expiry-alert-review-cron:0 30 2 * * *}",
            lockTimeout = "PT15M",
            maxRuntime = "PT10M")
    public int review(JobExecution execution) {
        ScopeContext view = execution.federationView();
        List<ExpiryTaskStore.LocationRef> activeLocations = taskStore.findActiveTaskLocations(view);

        int totalEvaluated = 0;
        int totalAlertsEmitted = 0;

        for (ExpiryTaskStore.LocationRef ref : activeLocations) {
            ScopeContext ownScope = execution.ownScopeOf(ref.ownerEntityId());
            try {
                ExpiryAlertScanResult result = evaluationHandler.evaluateLocation(ref.locationId(), ownScope);
                totalEvaluated += result.totalEvaluated();
                totalAlertsEmitted += result.nearExpiryAlertsEmitted() + result.slowMovingAlertsEmitted();
            } catch (RuntimeException e) {
                log.error("Failed expiry and slow-moving alert review at location {}", ref.locationId(), e);
            }
        }

        execution.itemsProcessed(totalEvaluated);
        log.info(
                "ExpiryAlertReviewJob finished: {} items evaluated, {} alerts emitted",
                totalEvaluated,
                totalAlertsEmitted);
        return totalAlertsEmitted;
    }
}
