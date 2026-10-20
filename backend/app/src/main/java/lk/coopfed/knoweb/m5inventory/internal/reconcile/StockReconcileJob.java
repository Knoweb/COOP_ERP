package lk.coopfed.knoweb.m5inventory.internal.reconcile;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Nightly reconciliation job that verifies the core inventory ledger invariant: {@code
 * stock_lot.qty_on_hand} equals the sum of movements for that lot (doc 18 invariant B-I4; doc 25
 * sections 3.2, 10; 25A section 7.2; ticket M5-11 / INV-03).
 *
 * <p>Balances are materialized for performance and reconciled nightly. When drift between
 * movements and balances is detected, this job raises an audited {@code REVIEW} of type {@code
 * STOCK_DRIFT} with both figures (actual balance and sum of movements). It NEVER silently fixes,
 * updates balances, or rewrites movement history.
 */
@Component
public class StockReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(StockReconcileJob.class);

    public static final String AUDIT_STOCK_DRIFT = "STOCK_DRIFT";

    private final StockReconcileStore store;
    private final StockDriftReporter reporter;

    public StockReconcileJob(StockReconcileStore store, StockDriftReporter reporter) {
        this.store = store;
        this.reporter = reporter;
    }

    /**
     * Nightly scan across all locations in the federation (03:00 Colombo time).
     */
    @ScheduledJob(
            name = "inventory-stock-reconcile",
            cron = "${coop-erp.m5.stock-reconcile-cron:0 0 3 * * *}",
            lockTimeout = "PT30M",
            maxRuntime = "PT15M")
    public int reconcile(JobExecution execution) {
        ScopeContext view = execution.federationView();
        List<StockReconcileStore.DriftingLot> drifts = store.findDriftingLots(view);

        int reported = 0;
        for (StockReconcileStore.DriftingLot drift : drifts) {
            ScopeContext ownScope = execution.ownScopeOf(drift.ownerEntityId());
            try {
                if (reporter.reportDrift(drift, ownScope)) {
                    reported++;
                }
            } catch (RuntimeException e) {
                log.error("Failed to record drift review for lot {}", drift.stockLotId(), e);
            }
        }

        execution.itemsProcessed(reported);
        return reported;
    }

    /**
     * Reconcile lots for a single location under its entity's OWN scope.
     *
     * @param locationId location to reconcile
     * @param scope caller's OWN scope
     * @return number of newly raised drift reviews
     */
    public int reconcileLocation(UUID locationId, ScopeContext scope) {
        List<StockReconcileStore.DriftingLot> drifts = store.findDriftingLots(locationId, scope);
        int reported = 0;
        for (StockReconcileStore.DriftingLot drift : drifts) {
            if (reporter.reportDrift(drift, scope)) {
                reported++;
            }
        }
        return reported;
    }

    /**
     * Report drift for one stock lot, delegating to {@link StockDriftReporter} so that each review
     * runs in an active entity-scoped transaction.
     */
    public boolean reportDrift(StockReconcileStore.DriftingLot drift, ScopeContext scope) {
        return reporter.reportDrift(drift, scope);
    }
}
