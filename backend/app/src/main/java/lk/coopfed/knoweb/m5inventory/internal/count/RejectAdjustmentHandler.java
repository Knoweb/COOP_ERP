package lk.coopfed.knoweb.m5inventory.internal.count;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.AdjustmentRejected;
import lk.coopfed.knoweb.m5inventory.api.RejectAdjustment;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RejectAdjustment (25A section 6.3: "reason → REJECTED; count CLOSED"): the variances beyond
 * tolerance are not posted, typically to count again. What was within tolerance stays posted.
 *
 * <p>Guards, in order: an OWN scope; the task visible ({@code m5.count.not_found}); in
 * VARIANCE_REVIEW ({@code m5.adjustment.not_in_review}); a reason ({@code m5.reason_required}).
 *
 * <p>Mutation: CLOSED (REJECTED) with the reason. Audit {@code ADJUSTMENT_REJECTED} with the
 * reason; event {@code adjustment.rejected.v1}.
 */
@Service
@CommandHandler(permission = "inv.adjust.approve", requiresMfa = true)
class RejectAdjustmentHandler implements Handles<RejectAdjustment, UUID> {

    static final String AUDIT_REJECTED = "ADJUSTMENT_REJECTED";

    private final CountStore store;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    RejectAdjustmentHandler(
            CountStore store, JdbcTemplate jdbc, Clock clock, AuditFacade audit, EventPublisher events) {
        this.store = store;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RejectAdjustment command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        CountStore.Task task = store.lock(command.taskId())
                .orElseThrow(() ->
                        new ProblemException("m5.count.not_found", Map.of("taskId", String.valueOf(command.taskId()))));
        if (!"VARIANCE_REVIEW".equals(task.status())) {
            throw new ProblemException("m5.adjustment.not_in_review");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw new ProblemException("m5.reason_required");
        }
        String reason = command.reason().strip();
        jdbc.update(
                """
                update inventory.count_task
                   set status = 'CLOSED', outcome = 'REJECTED', reviewed_by = ?, reviewed_at = ?, review_reason = ?
                 where task_id = ?
                """,
                scope.userId(),
                Timestamp.from(clock.instant()),
                reason,
                task.taskId());

        audit.record(
                AUDIT_REJECTED,
                Subject.of("count_task", task.taskId()),
                Map.of("status", "VARIANCE_REVIEW"),
                Map.of("status", "CLOSED", "outcome", "REJECTED"),
                scope,
                reason);
        events.publish(
                new AdjustmentRejected(task.taskId(), task.ownerEntityId(), task.locationId(), task.reviewValue()));
        return task.taskId();
    }
}
