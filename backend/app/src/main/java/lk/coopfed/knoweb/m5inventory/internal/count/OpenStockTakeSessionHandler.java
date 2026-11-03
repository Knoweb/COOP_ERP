package lk.coopfed.knoweb.m5inventory.internal.count;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m5inventory.api.OpenStockTakeSession;
import lk.coopfed.knoweb.m5inventory.api.StockTakeSessionOpened;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OpenStockTakeSessionHandler (PGL-M2-09): opens or resumes a stock-take session for a shop location.
 * Directly integrates with M5's count lifecycle (SCHEDULED -> COUNTING) while enabling mobile clients
 * to start counting without requiring an advance scheduling step.
 */
@Service
@CommandHandler(permission = "shop.count.record")
class OpenStockTakeSessionHandler implements Handles<OpenStockTakeSession, UUID> {

    static final String AUDIT_OPENED = "STOCK_TAKE_SESSION_OPENED";

    private final CountStore countStore;
    private final StockTakeStore stockTakeStore;
    private final CatalogueQueries catalogueQueries;
    private final ControlPolicy policy;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId zone;
    private final AuditFacade audit;
    private final EventPublisher events;

    OpenStockTakeSessionHandler(
            CountStore countStore,
            StockTakeStore stockTakeStore,
            CatalogueQueries catalogueQueries,
            ControlPolicy policy,
            JdbcTemplate jdbc,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String zone,
            AuditFacade audit,
            EventPublisher events) {
        this.countStore = countStore;
        this.stockTakeStore = stockTakeStore;
        this.catalogueQueries = catalogueQueries;
        this.policy = policy;
        this.jdbc = jdbc;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(OpenStockTakeSession command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        if (command.locationId() == null) {
            throw new ProblemException("m5.location.required");
        }
        policy.requireEntityLocation(command.locationId(), scope);

        // Check if an open count task already exists for this location
        var active = stockTakeStore.activeTask(command.locationId());
        if (active.isPresent()) {
            CountStore.Task task = active.get();
            if ("VARIANCE_REVIEW".equals(task.status())) {
                throw new ProblemException(
                        "m5.count.in_review", Map.of("taskId", task.taskId().toString()));
            }
            if ("SCHEDULED".equals(task.status())) {
                List<CountStore.Lot> lots = countStore.lotsInScope(task);
                for (CountStore.Lot lot : lots) {
                    jdbc.update(
                            """
                            insert into inventory.count_expectation
                                (task_id, owner_entity_id, location_id, batch_id, sku_id, condition, expected_qty)
                            values (?, ?, ?, ?, ?, ?, ?)
                            """,
                            task.taskId(),
                            task.ownerEntityId(),
                            task.locationId(),
                            lot.batchId(),
                            lot.skuId(),
                            lot.condition(),
                            lot.qtyOnHand());
                }
                jdbc.update(
                        "update inventory.count_task set status = 'COUNTING', started_by = ?, started_at = ? where task_id = ?",
                        scope.userId(),
                        Timestamp.from(clock.instant()),
                        task.taskId());

                int expectedCount = stockTakeStore.expectedLotsCount(task.taskId());
                audit.record(
                        AUDIT_OPENED,
                        Subject.of("stock_take_session", task.taskId()),
                        Map.of("status", "SCHEDULED"),
                        Map.of("status", "COUNTING", "resumed", true, "expectedLots", expectedCount),
                        scope);
                events.publish(new StockTakeSessionOpened(
                        task.taskId(), task.ownerEntityId(), task.locationId(), task.scopeKind(), expectedCount));
                return task.taskId();
            }
            if ("COUNTING".equals(task.status())) {
                return task.taskId();
            }
        }

        // No active count exists: schedule and start in one seamless workflow
        String kind = (command.scopeKind() == null || command.scopeKind().isBlank()) ? "FULL" : command.scopeKind();
        List<UUID> skus = command.skuIds() == null ? List.of() : List.copyOf(command.skuIds());
        boolean full = "FULL".equals(kind);
        boolean bySku = "SKUS".equals(kind)
                && !skus.isEmpty()
                && skus.stream()
                        .allMatch(sku -> sku != null
                                && catalogueQueries.getSku(sku, scope).isPresent());
        if (!full && !bySku) {
            throw new ProblemException("m5.count.scope_invalid");
        }
        if (countStore.openAt(command.locationId())) {
            throw new ProblemException("m5.count.already_open");
        }

        UUID taskId = Ids.next();
        LocalDate day = LocalDate.ofInstant(clock.instant(), zone);
        Instant now = clock.instant();
        UUID[] skuArray = skus.isEmpty() ? null : skus.toArray(UUID[]::new);

        jdbc.update(
                """
                insert into inventory.count_task
                    (task_id, owner_entity_id, location_id, scope_kind, scope_sku_ids, scheduled_for, scheduled_by,
                     status, started_by, started_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?, 'COUNTING', ?, ?, ?)
                """,
                taskId,
                scope.entityId(),
                command.locationId(),
                kind,
                skuArray,
                day,
                scope.userId(),
                scope.userId(),
                Timestamp.from(now),
                Timestamp.from(now));

        CountStore.Task newTask = new CountStore.Task(
                taskId, scope.entityId(), command.locationId(), kind, skus, "COUNTING", null, null, null, now);

        List<CountStore.Lot> lots = countStore.lotsInScope(newTask);
        for (CountStore.Lot lot : lots) {
            jdbc.update(
                    """
                    insert into inventory.count_expectation
                        (task_id, owner_entity_id, location_id, batch_id, sku_id, condition, expected_qty)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """,
                    taskId,
                    scope.entityId(),
                    command.locationId(),
                    lot.batchId(),
                    lot.skuId(),
                    lot.condition(),
                    lot.qtyOnHand());
        }

        int expectedLots = stockTakeStore.expectedLotsCount(taskId);
        audit.record(
                AUDIT_OPENED,
                Subject.of("stock_take_session", taskId),
                null,
                Map.of(
                        "locationId",
                        command.locationId(),
                        "scopeKind",
                        kind,
                        "status",
                        "COUNTING",
                        "expectedLots",
                        expectedLots),
                scope);
        events.publish(new StockTakeSessionOpened(taskId, scope.entityId(), command.locationId(), kind, expectedLots));
        return taskId;
    }
}
