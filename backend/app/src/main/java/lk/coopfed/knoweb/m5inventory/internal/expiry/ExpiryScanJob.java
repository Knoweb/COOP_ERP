package lk.coopfed.knoweb.m5inventory.internal.expiry;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.api.CloseExpiryTask;
import lk.coopfed.knoweb.m5inventory.api.OpenExpiryTasks;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Nightly scheduled job scanning stock lots entering their expiry warning window (doc 25 section 4.6; 25A section 7.1).
 *
 * <p>For each holding location:
 * <ul>
 *   <li>Evaluates the entity's configured {@code inventory.expiry_warning_days} (default 30).
 *   <li>Identifies active positive GOOD stock lots entering the warning window.
 *   <li>Closes active expiry tasks whose lots have been exhausted (quantity &le; 0).
 *   <li>Opens an {@code expiry_task} per entering batch and creates/links a {@code SKUS} count task.
 *   <li>Respects {@code count_task_open_per_location} and defers count scheduling safely if an active count occupies the location.
 * </ul>
 */
@Component
public class ExpiryScanJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiryScanJob.class);

    private final ExpiryTaskStore store;
    private final ControlPolicy policy;
    private final OpenExpiryTasksHandler openHandler;
    private final CloseExpiryTaskHandler closeHandler;
    private final Clock clock;
    private final ZoneId businessZone;

    ExpiryScanJob(
            ExpiryTaskStore store,
            ControlPolicy policy,
            OpenExpiryTasksHandler openHandler,
            CloseExpiryTaskHandler closeHandler,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone) {
        this.store = store;
        this.policy = policy;
        this.openHandler = openHandler;
        this.closeHandler = closeHandler;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
    }

    @ScheduledJob(
            name = "inventory-expiry-scan",
            cron = "${coop-erp.m5.expiry-scan-cron:0 0 2 * * *}",
            lockTimeout = "PT15M",
            maxRuntime = "PT10M")
    public int scan(JobExecution execution) {
        ScopeContext view = execution.federationView();
        LocalDate today = LocalDate.ofInstant(clock.instant(), businessZone);

        // 1. Discover all candidate lots across the fleet
        List<ExpiryTaskStore.CandidateLot> candidates = store.findCandidateLots(today, view);

        // Group candidate lots by entity -> location
        Map<UUID, Map<UUID, List<ExpiryTaskStore.CandidateLot>>> entityLocations = new LinkedHashMap<>();
        for (ExpiryTaskStore.CandidateLot lot : candidates) {
            entityLocations
                    .computeIfAbsent(lot.ownerEntityId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(lot.locationId(), k -> new ArrayList<>())
                    .add(lot);
        }

        // Also ensure all locations with active or pending unlinked expiry tasks are processed
        List<ExpiryTaskStore.LocationRef> activeLocations = store.findActiveTaskLocations(view);
        for (ExpiryTaskStore.LocationRef ref : activeLocations) {
            entityLocations
                    .computeIfAbsent(ref.ownerEntityId(), k -> new LinkedHashMap<>())
                    .putIfAbsent(ref.locationId(), new ArrayList<>());
        }

        int processed = 0;
        for (Map.Entry<UUID, Map<UUID, List<ExpiryTaskStore.CandidateLot>>> entityEntry : entityLocations.entrySet()) {
            UUID entityId = entityEntry.getKey();
            ScopeContext ownScope = execution.ownScopeOf(entityId);
            int warningDays = policy.expiryWarningDays(ownScope);

            for (Map.Entry<UUID, List<ExpiryTaskStore.CandidateLot>> locEntry :
                    entityEntry.getValue().entrySet()) {
                UUID locationId = locEntry.getKey();
                List<ExpiryTaskStore.CandidateLot> lots = locEntry.getValue();

                try {
                    processed += processLocation(locationId, lots, today, warningDays, ownScope);
                } catch (ProblemException e) {
                    log.error("Failed to process expiry scan for location {}", locationId, e);
                } catch (RuntimeException e) {
                    log.error("Unexpected error during expiry scan for location {}", locationId, e);
                }
            }
        }

        execution.itemsProcessed(processed);
        return processed;
    }

    /**
     * Process entering and active expiry tasks for one location under its entity's OWN scope.
     */
    public int processLocation(
            UUID locationId,
            List<ExpiryTaskStore.CandidateLot> candidateLots,
            LocalDate today,
            int warningDays,
            ScopeContext scope) {

        int count = 0;

        // 1. Close any active tasks where the lot is exhausted (qty_on_hand <= 0)
        List<ExpiryTaskStore.ExpiryTask> activeTasks = store.findActiveTasks(locationId);
        for (ExpiryTaskStore.ExpiryTask active : activeTasks) {
            if (store.currentOnHand(locationId, active.batchId()).signum() <= 0) {
                closeHandler.handle(new CloseExpiryTask(active.taskId(), "LOT_EXHAUSTED"), scope);
                count++;
            }
        }

        // 2. Filter candidate lots entering within the warning window
        List<OpenExpiryTasks.Batch> enteringBatches = new ArrayList<>();
        if (candidateLots != null) {
            for (ExpiryTaskStore.CandidateLot lot : candidateLots) {
                int daysToExpiry = (int) ChronoUnit.DAYS.between(today, lot.expiryDate());
                if (daysToExpiry <= warningDays) {
                    enteringBatches.add(
                            new OpenExpiryTasks.Batch(lot.batchId(), lot.skuId(), daysToExpiry, lot.qtyOnHand()));
                }
            }
        }

        // 3. Open tasks and coordinate count task scheduling (also picks up pending unlinked tasks)
        List<UUID> opened = openHandler.handle(new OpenExpiryTasks(locationId, enteringBatches), scope);
        count += opened.size();

        return count;
    }

    /** The business zone used for date calculations. */
    public ZoneId businessZone() {
        return businessZone;
    }
}
