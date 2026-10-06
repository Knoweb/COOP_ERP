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
import lk.coopfed.knoweb.kernel.api.Sod;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.AdjustmentApproved;
import lk.coopfed.knoweb.m5inventory.api.ApproveAdjustment;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ApproveAdjustment (25A section 6.3: "SUBMITTED; Sod.assertDistinct(request, approve,
 * requester); approver limit ≥ value; MFA → APPROVED → COUNT_ADJUST movements → POSTED; count
 * task CLOSED"; doc 25 flow 6.3).
 *
 * <p>Guards, in order: an OWN scope; the task visible ({@code m5.count.not_found}); in
 * VARIANCE_REVIEW ({@code m5.adjustment.not_in_review}); the approver not the person who submitted
 * the count ({@code m5.adjustment.approver_is_requester}, and the kernel's {@code sod.same_person}
 * for the pair inv.adjust.request / inv.adjust.approve); the value within the approver's limit,
 * which fails closed since wave 2 (M5-09: no grant at the entity is no authority, a grant without
 * a limit approves band 1 only, a variance of no cost needs band 2; {@code
 * m5.approval.limit_exceeded}: the next band acts). The fresh second factor is the
 * kernel's (the permission requires it).
 *
 * <p>Mutation: COUNT_ADJUST for each variance beyond tolerance, citing the count, as recorded at
 * submit (a variance is the difference found, so what moved since does not change it; the lines
 * within tolerance were posted at submit and are never posted again); CLOSED (APPROVED). Audit
 * {@code ADJUSTMENT_APPROVED}; event {@code adjustment.approved.v1}.
 */
@Service
@CommandHandler(permission = "inv.adjust.approve", requiresMfa = true)
class ApproveAdjustmentHandler implements Handles<ApproveAdjustment, UUID> {

    static final String AUDIT_APPROVED = "ADJUSTMENT_APPROVED";

    private final CountStore store;
    private final ControlPolicy policy;
    private final Sod sod;
    private final StockLedger ledger;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    @SuppressWarnings("java:S107") // the collaborators of one command, each used once
    ApproveAdjustmentHandler(
            CountStore store,
            ControlPolicy policy,
            Sod sod,
            StockLedger ledger,
            JdbcTemplate jdbc,
            Clock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.policy = policy;
        this.sod = sod;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ApproveAdjustment command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        CountStore.Task task = store.lock(command.taskId())
                .orElseThrow(() ->
                        new ProblemException("m5.count.not_found", Map.of("taskId", String.valueOf(command.taskId()))));
        if (!"VARIANCE_REVIEW".equals(task.status())) {
            throw new ProblemException("m5.adjustment.not_in_review");
        }
        if (scope.userId() != null && scope.userId().equals(task.submittedBy())) {
            throw new ProblemException("m5.adjustment.approver_is_requester");
        }
        sod.assertDistinct(scope, "inv.adjust.request", "inv.adjust.approve", task.submittedBy());
        List<CountStore.Line> lines = store.lines(task.taskId());
        // wave 2, M5-09: a variance of an item with no cost routes the adjustment to band 2.
        boolean zeroCostLine = lines.stream()
                .anyMatch(line -> !line.withinTolerance()
                        && line.varianceQty().signum() != 0
                        && line.unitCost().signum() == 0);
        policy.requireWithinLimit(task.reviewValue(), zeroCostLine, "inv.adjust.approve", scope);

        List<Movement> movements = lines.stream()
                .filter(line -> !line.withinTolerance() && line.varianceQty().signum() != 0)
                .map(line -> new Movement(
                        task.locationId(),
                        line.batchId(),
                        LotCondition.valueOf(line.condition()),
                        MovementType.COUNT_ADJUST,
                        line.varianceQty(),
                        null,
                        line.lineId()))
                .toList();
        if (!movements.isEmpty()) {
            ledger.post(new PostMovements(task.taskId(), null, null, movements), scope);
        }
        jdbc.update(
                """
                update inventory.count_task
                   set status = 'CLOSED', outcome = 'APPROVED', reviewed_by = ?, reviewed_at = ?
                 where task_id = ?
                """,
                scope.userId(),
                Timestamp.from(clock.instant()),
                task.taskId());

        audit.record(
                AUDIT_APPROVED,
                Subject.of("count_task", task.taskId()),
                Map.of("status", "VARIANCE_REVIEW"),
                Map.of(
                        "status", "CLOSED",
                        "outcome", "APPROVED",
                        "value", task.reviewValue().toPlainString(),
                        "band", task.reviewBand(),
                        "lines", movements.size()),
                scope);
        events.publish(new AdjustmentApproved(
                task.taskId(),
                task.ownerEntityId(),
                task.locationId(),
                task.reviewValue(),
                task.reviewBand(),
                scope.userId()));
        return task.taskId();
    }
}
