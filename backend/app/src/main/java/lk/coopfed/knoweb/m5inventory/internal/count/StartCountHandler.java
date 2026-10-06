package lk.coopfed.knoweb.m5inventory.internal.count;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.CountStarted;
import lk.coopfed.knoweb.m5inventory.api.StartCount;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * StartCount (25A section 6.3: "SCHEDULED; expectation snapshot = lots in scope now"): the
 * counter begins, at the location or from an entity-wide session.
 *
 * <p>Guards, in order: an OWN scope; the task visible to the scope ({@code m5.count.not_found}: a
 * shop session sees its own shop's counts only); SCHEDULED ({@code m5.count.not_scheduled}).
 *
 * <p>Mutation: one expectation row per lot in scope with what the book holds now (negative lots
 * too: the count is what corrects them); COUNTING. Audit {@code COUNT_STARTED}; event
 * {@code count.started.v1}. Nothing is frozen (doc 25 DR-8, E-06): sales and receipts go on, and
 * the submit measures each lot against the book as it stands then.
 */
@Service
@CommandHandler(permission = "shop.count.record")
class StartCountHandler implements Handles<StartCount, UUID> {

    static final String AUDIT_STARTED = "COUNT_STARTED";

    private final CountStore store;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    StartCountHandler(CountStore store, JdbcTemplate jdbc, Clock clock, AuditFacade audit, EventPublisher events) {
        this.store = store;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(StartCount command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        CountStore.Task task = store.lock(command.taskId())
                .orElseThrow(() ->
                        new ProblemException("m5.count.not_found", Map.of("taskId", String.valueOf(command.taskId()))));
        if (!"SCHEDULED".equals(task.status())) {
            throw new ProblemException("m5.count.not_scheduled");
        }

        List<CountStore.Lot> lots = store.lotsInScope(task);
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

        audit.record(
                AUDIT_STARTED,
                Subject.of("count_task", task.taskId()),
                Map.of("status", "SCHEDULED"),
                Map.of("status", "COUNTING", "lots", lots.size()),
                scope);
        events.publish(new CountStarted(task.taskId(), task.ownerEntityId(), task.locationId(), lots.size()));
        return task.taskId();
    }
}
