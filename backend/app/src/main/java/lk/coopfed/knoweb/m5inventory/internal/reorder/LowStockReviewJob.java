package lk.coopfed.knoweb.m5inventory.internal.reorder;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Scheduled nightly sweep that evaluates stock levels against reorder controls across the federation (PGL-M2-01).
 *
 * <p>Finds all locations with configured reorder controls and evaluates them under their owner's OWN scope.
 * Any item at or below its reorder level that has not yet been alerted emits an alert and marks triggered.
 * Any replenished item above its reorder level is re-armed.
 */
@Component
public class LowStockReviewJob {

    private static final Logger log = LoggerFactory.getLogger(LowStockReviewJob.class);

    private final ReorderStore store;
    private final LowStockEvaluationHandler evaluationHandler;

    public LowStockReviewJob(ReorderStore store, LowStockEvaluationHandler evaluationHandler) {
        this.store = store;
        this.evaluationHandler = evaluationHandler;
    }

    @ScheduledJob(
            name = "inventory-low-stock-review",
            cron = "${coop-erp.m5.low-stock-review-cron:0 15 2 * * *}",
            lockTimeout = "PT15M",
            maxRuntime = "PT10M")
    public int review(JobExecution execution) {
        ScopeContext view = execution.federationView();
        List<ReorderStore.LocationEntityKey> targets = store.findDistinctLocationsWithControls(view);

        int alertsTotal = 0;
        int evaluatedTotal = 0;
        for (ReorderStore.LocationEntityKey target : targets) {
            ScopeContext ownScope = execution.ownScopeOf(target.ownerEntityId());
            try {
                LowStockEvaluationResult result = evaluationHandler.evaluateLocation(target.locationId(), ownScope);
                evaluatedTotal += result.evaluatedCount();
                alertsTotal += result.alertsTriggeredCount();
            } catch (RuntimeException e) {
                log.error("Failed low stock review at location {}", target.locationId(), e);
            }
        }

        execution.itemsProcessed(evaluatedTotal);
        log.info("LowStockReviewJob finished: {} controls evaluated, {} alerts emitted", evaluatedTotal, alertsTotal);
        return alertsTotal;
    }
}
