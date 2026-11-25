package lk.coopfed.knoweb.m5inventory.internal.expiry;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.CountScheduled;
import lk.coopfed.knoweb.m5inventory.api.ExpiryTaskOpened;
import lk.coopfed.knoweb.m5inventory.api.OpenExpiryTasks;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens expiry review tasks for batches entering their window and coordinates count task scheduling
 * (doc 25 section 4.6; 25A section 7.1).
 *
 * <p>Respects {@code count_task_open_per_location}:
 * <ul>
 *   <li>Consolidates multiple expiring batches at one location into a single {@code SKUS} count task.
 *   <li>Reuses an existing open {@code FULL} or covering {@code SKUS} count task if already present.
 *   <li>Defers count scheduling safely if an active incompatible count is in progress.
 * </ul>
 */
@Service
@CommandHandler(permission = "inv.count.schedule")
public class OpenExpiryTasksHandler implements Handles<OpenExpiryTasks, List<UUID>> {

    public static final String AUDIT_EXPIRY_OPENED = "EXPIRY_TASK_OPENED";
    public static final String AUDIT_COUNT_SCHEDULED = "COUNT_SCHEDULED";

    private final ExpiryTaskStore store;
    private final ControlPolicy policy;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId zone;
    private final AuditFacade audit;
    private final EventPublisher events;

    OpenExpiryTasksHandler(
            ExpiryTaskStore store,
            ControlPolicy policy,
            JdbcTemplate jdbc,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String zone,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.policy = policy;
        this.jdbc = jdbc;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public List<UUID> handle(OpenExpiryTasks command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        policy.requireEntityLocation(command.locationId(), scope);

        List<OpenExpiryTasks.Batch> incoming = command.batches() == null ? List.of() : command.batches();
        List<ExpiryTaskStore.ExpiryTask> pendingUnlinked = store.findPendingUnlinkedTasks(command.locationId(), scope);

        if (incoming.isEmpty() && pendingUnlinked.isEmpty()) {
            return List.of();
        }

        Optional<ExpiryTaskStore.OpenCount> openCount = store.findOpenCountTask(command.locationId(), scope);
        UUID targetCountTaskId = null;

        if (openCount.isPresent()) {
            ExpiryTaskStore.OpenCount current = openCount.get();
            if ("FULL".equals(current.scopeKind())) {
                targetCountTaskId = current.taskId();
            } else if ("SKUS".equals(current.scopeKind())) {
                Set<UUID> openSkus = new LinkedHashSet<>(current.scopeSkuIds());
                boolean allCovered = incoming.stream().allMatch(b -> openSkus.contains(b.skuId()))
                        && pendingUnlinked.stream().allMatch(p -> openSkus.contains(p.skuId()));
                if (allCovered) {
                    targetCountTaskId = current.taskId();
                }
                // Otherwise an incompatible count occupies the location; scheduling is deferred.
            }
        } else {
            // No count task is open: create one SKUS-scoped count task covering all incoming and pending SKUs
            Set<UUID> skusToCount = new LinkedHashSet<>();
            for (OpenExpiryTasks.Batch b : incoming) {
                skusToCount.add(b.skuId());
            }
            for (ExpiryTaskStore.ExpiryTask p : pendingUnlinked) {
                skusToCount.add(p.skuId());
            }

            if (!skusToCount.isEmpty()) {
                UUID newCountId = Ids.next();
                LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
                Timestamp now = Timestamp.from(clock.instant());

                jdbc.update(
                        """
                        insert into inventory.count_task
                            (task_id, owner_entity_id, location_id, scope_kind, scope_sku_ids, scheduled_for,
                             scheduled_by, scheduled_at, status)
                        values (?, ?, ?, 'SKUS', ?, ?, ?, ?, 'SCHEDULED')
                        """,
                        newCountId,
                        scope.entityId(),
                        command.locationId(),
                        skusToCount.toArray(new UUID[0]),
                        today,
                        scope.userId(),
                        now);

                audit.record(
                        AUDIT_COUNT_SCHEDULED,
                        Subject.of("count_task", newCountId),
                        null,
                        Map.of(
                                "locationId", command.locationId(),
                                "scopeKind", "SKUS",
                                "skus", skusToCount.size(),
                                "scheduledFor", today.toString(),
                                "status", "SCHEDULED"),
                        scope);
                events.publish(new CountScheduled(newCountId, scope.entityId(), command.locationId(), "SKUS"));

                targetCountTaskId = newCountId;
            }
        }

        // If a count task is targeted (either newly created or covering), link any pending unlinked tasks
        if (targetCountTaskId != null && !pendingUnlinked.isEmpty()) {
            jdbc.update(
                    """
                    update inventory.expiry_task
                       set count_task_id = ?
                     where location_id = ?
                       and status = 'OPEN'
                       and count_task_id is null
                    """,
                    targetCountTaskId,
                    command.locationId());
        }

        List<UUID> createdIds = new ArrayList<>();
        Timestamp now = Timestamp.from(clock.instant());

        for (OpenExpiryTasks.Batch b : incoming) {
            Boolean exists = jdbc.queryForObject(
                    """
                    select exists (
                        select 1 from inventory.expiry_task
                         where location_id = ? and batch_id = ? and status <> 'CLOSED'
                    )
                    """,
                    Boolean.class,
                    command.locationId(),
                    b.batchId());

            if (Boolean.TRUE.equals(exists)) {
                continue;
            }

            UUID taskId = Ids.next();
            jdbc.update(
                    """
                    insert into inventory.expiry_task
                        (task_id, owner_entity_id, location_id, batch_id, sku_id, days_to_expiry,
                         qty_at_open, status, count_task_id, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)
                    """,
                    taskId,
                    scope.entityId(),
                    command.locationId(),
                    b.batchId(),
                    b.skuId(),
                    b.daysToExpiry(),
                    b.qtyAtOpen(),
                    targetCountTaskId,
                    now);

            Map<String, Object> auditContext = new LinkedHashMap<>();
            auditContext.put("locationId", command.locationId());
            auditContext.put("batchId", b.batchId());
            auditContext.put("skuId", b.skuId());
            auditContext.put("daysToExpiry", b.daysToExpiry());
            auditContext.put("qtyAtOpen", b.qtyAtOpen().toPlainString());
            if (targetCountTaskId != null) {
                auditContext.put("countTaskId", targetCountTaskId);
            }

            audit.record(AUDIT_EXPIRY_OPENED, Subject.of("expiry_task", taskId), null, auditContext, scope);

            events.publish(new ExpiryTaskOpened(
                    taskId,
                    scope.entityId(),
                    command.locationId(),
                    b.batchId(),
                    b.skuId(),
                    b.daysToExpiry(),
                    b.qtyAtOpen(),
                    targetCountTaskId));

            createdIds.add(taskId);
        }

        return createdIds;
    }
}
