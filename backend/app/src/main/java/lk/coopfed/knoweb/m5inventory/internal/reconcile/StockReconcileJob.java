package lk.coopfed.knoweb.m5inventory.internal.reconcile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.AuditReviewReader;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
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
    private final AuditFacade audit;
    private final AuditReviewReader reviews;

    public StockReconcileJob(StockReconcileStore store, AuditFacade audit, AuditReviewReader reviews) {
        this.store = store;
        this.audit = audit;
        this.reviews = reviews;
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
                if (reportDrift(drift, ownScope)) {
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
            if (reportDrift(drift, scope)) {
                reported++;
            }
        }
        return reported;
    }

    /**
     * Report drift for one stock lot, deduplicated by the lot's current drift amount so that repeated
     * job runs do not produce duplicate unresolved reviews.
     */
    public boolean reportDrift(StockReconcileStore.DriftingLot drift, ScopeContext scope) {
        // Prevent duplicate unresolved reviews for the same drift
        boolean alreadyReported = reviews.reviewExists(
                AUDIT_STOCK_DRIFT, drift.stockLotId(), "drift", drift.drift().toPlainString(), scope);

        if (alreadyReported) {
            log.debug(
                    "Drift of {} on lot {} has already been reported; skipping duplicate review",
                    drift.drift(),
                    drift.stockLotId());
            return false;
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("stockLotId", drift.stockLotId().toString());
        details.put("locationId", drift.locationId().toString());
        details.put("batchId", drift.batchId().toString());
        details.put("skuId", drift.skuId().toString());
        details.put("condition", drift.condition());
        details.put("qtyOnHand", drift.qtyOnHand().toPlainString());
        details.put("movementSum", drift.movementSum().toPlainString());
        details.put("drift", drift.drift().toPlainString());

        audit.record(
                AUDIT_STOCK_DRIFT,
                Subject.of("stock_lot", drift.stockLotId()),
                null,
                details,
                scope,
                "Stock lot quantity on hand drifted from sum of movements");

        log.warn(
                "STOCK_DRIFT detected for lot {} at location {}: qtyOnHand={}, movementSum={}, drift={}",
                drift.stockLotId(),
                drift.locationId(),
                drift.qtyOnHand(),
                drift.movementSum(),
                drift.drift());

        return true;
    }
}
