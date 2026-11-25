package lk.coopfed.knoweb.m5inventory.internal.reconcile;

import java.util.LinkedHashMap;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.AuditReviewReader;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional reporter for stock balance drift audit reviews (doc 18 invariant B-I4; doc 25
 * sections 3.2, 10; 25A section 7.2; ticket M5-11 / INV-03).
 *
 * <p>Each review executes in a transaction of its own ({@code REQUIRES_NEW}) in the OWN scope of
 * the drifting lot's owner entity. The {@code @Transactional} annotation triggers the kernel's
 * {@code ScopeConnectionCustomizer} aspect to set the PostgreSQL RLS session variables ({@code
 * kernel.scope_class}, {@code kernel.scope_entity}, etc.) before checking existing reviews or
 * inserting into {@code kernel.audit_event}.
 */
@Component
public class StockDriftReporter {

    private static final Logger log = LoggerFactory.getLogger(StockDriftReporter.class);

    private final AuditFacade audit;
    private final AuditReviewReader reviews;

    public StockDriftReporter(AuditFacade audit, AuditReviewReader reviews) {
        this.audit = audit;
        this.reviews = reviews;
    }

    /**
     * Report drift for one stock lot under its entity's OWN scope.
     *
     * @param drift drifting lot details
     * @param scope caller's scope (typically {@code execution.ownScopeOf(drift.ownerEntityId())})
     * @return true if a new review was recorded, false if an unresolved review already existed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reportDrift(StockReconcileStore.DriftingLot drift, ScopeContext scope) {
        // Prevent duplicate unresolved reviews for the same drift
        boolean alreadyReported = reviews.reviewExists(
                StockReconcileJob.AUDIT_STOCK_DRIFT,
                drift.stockLotId(),
                "drift",
                drift.drift().toPlainString(),
                scope);

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
                StockReconcileJob.AUDIT_STOCK_DRIFT,
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
