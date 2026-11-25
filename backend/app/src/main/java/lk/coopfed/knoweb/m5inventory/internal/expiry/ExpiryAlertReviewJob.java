package lk.coopfed.knoweb.m5inventory.internal.expiry;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.LocationFilter;
import lk.coopfed.knoweb.m1party.query.LocationPage;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
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
    private static final int LOCATION_PAGE_SIZE = 100;

    private final ExpiryTaskStore taskStore;
    private final EvaluateExpiryAlertsHandler evaluationHandler;
    private final PartyQueries partyQueries;

    public ExpiryAlertReviewJob(
            ExpiryTaskStore taskStore, EvaluateExpiryAlertsHandler evaluationHandler, PartyQueries partyQueries) {
        this.taskStore = taskStore;
        this.evaluationHandler = evaluationHandler;
        this.partyQueries = partyQueries;
    }

    @ScheduledJob(
            name = "inventory-expiry-alert-review",
            cron = "${coop-erp.m5.expiry-alert-review-cron:0 30 2 * * *}",
            lockTimeout = "PT15M",
            maxRuntime = "PT10M")
    public int review(JobExecution execution) {
        ScopeContext view = execution.federationView();
        Set<ExpiryTaskStore.LocationRef> stockLocations = new HashSet<>(taskStore.findLocationsWithPositiveStock(view));

        int totalEvaluated = 0;
        int totalAlertsEmitted = 0;

        UUID cursor = null;
        do {
            LocationPage page = partyQueries.listLocations(
                    new LocationFilter("ACTIVE", null, null, cursor, LOCATION_PAGE_SIZE), view);
            for (LocationView location : page.items()) {
                if (!isStockHoldingLocation(location)) {
                    continue;
                }
                ExpiryTaskStore.LocationRef ref =
                        new ExpiryTaskStore.LocationRef(location.ownerEntityId(), location.locationId());
                if (!stockLocations.remove(ref)) {
                    continue;
                }

                ScopeContext ownScope = execution.ownScopeOf(ref.ownerEntityId());
                try {
                    ExpiryAlertScanResult result = evaluationHandler.evaluateLocation(ref.locationId(), ownScope);
                    totalEvaluated += result.totalEvaluated();
                    totalAlertsEmitted += result.nearExpiryAlertsEmitted() + result.slowMovingAlertsEmitted();
                } catch (RuntimeException e) {
                    log.error("Failed expiry and slow-moving alert review at location {}", ref.locationId(), e);
                }
            }
            cursor = page.nextCursor() == null ? null : UUID.fromString(page.nextCursor());
        } while (cursor != null && !stockLocations.isEmpty());

        execution.itemsProcessed(totalEvaluated);
        log.info(
                "ExpiryAlertReviewJob finished: {} items evaluated, {} alerts emitted",
                totalEvaluated,
                totalAlertsEmitted);
        return totalAlertsEmitted;
    }

    private static boolean isStockHoldingLocation(LocationView location) {
        return "SHOP".equals(location.locationType()) || "WAREHOUSE".equals(location.locationType());
    }
}
