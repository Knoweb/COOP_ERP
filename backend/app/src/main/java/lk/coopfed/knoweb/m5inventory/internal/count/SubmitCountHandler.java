package lk.coopfed.knoweb.m5inventory.internal.count;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchView;
import lk.coopfed.knoweb.m5inventory.api.CountSubmitted;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.SubmitCount;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import lk.coopfed.knoweb.m5inventory.query.EntityCost;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SubmitCount (25A section 6.3: "COUNTING; every scope line counted or skipped with reason;
 * expectation adjusted by movements since start (E-06); VarianceEvaluator: in-tolerance variances
 * post COUNT_ADJUST now; others form ADJ → VARIANCE_REVIEW; else CLOSED").
 *
 * <p>Guards, in order: an OWN scope; the task visible ({@code m5.count.not_found}); COUNTING
 * ({@code m5.count.not_counting}); each line a batch and a condition with either a counted
 * quantity of zero or more (three decimals at most) or a reason to skip, never both, and no lot
 * twice ({@code m5.count.line_invalid}); the batch known to M2 ({@code m5.batch.not_found}) and of
 * an item in the count's scope ({@code m5.count.line_out_of_scope}); every lot noted at the start
 * counted or skipped ({@code m5.count.line_missing}).
 *
 * <p><b>The expectation during trading (doc 25 DR-8, E-06).</b> The book a line is measured
 * against is the lot as it stands at submit: the expectation at start plus every movement posted
 * since, which is exactly the lot's quantity now, since the ledger is the only writer. Sales made
 * while counting are therefore never reported as a variance, and nothing had to be frozen.
 *
 * <p>Mutation: a count line per submitted line with its variance (counted minus the book), its
 * value at the entity average and whether it is within tolerance ({@link ControlPolicy}); the
 * variances within tolerance posted at once as COUNT_ADJUST citing the count; the task
 * VARIANCE_REVIEW with the value and band of the rest, or CLOSED (POSTED) when there is no rest.
 * Audit {@code COUNT_SUBMITTED}; event {@code count.submitted.v1}.
 */
@Service
@CommandHandler(permission = "shop.count.record")
class SubmitCountHandler implements Handles<SubmitCount, UUID> {

    static final String AUDIT_SUBMITTED = "COUNT_SUBMITTED";

    private final CountStore store;
    private final ControlPolicy policy;
    private final BatchQueries batches;
    private final InventoryQueries inventory;
    private final StockLedger ledger;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    @SuppressWarnings("java:S107") // the collaborators of one command, each used once
    SubmitCountHandler(
            CountStore store,
            ControlPolicy policy,
            BatchQueries batches,
            InventoryQueries inventory,
            StockLedger ledger,
            JdbcTemplate jdbc,
            Clock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.policy = policy;
        this.batches = batches;
        this.inventory = inventory;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(SubmitCount command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        CountStore.Task task = store.lock(command.taskId())
                .orElseThrow(() ->
                        new ProblemException("m5.count.not_found", Map.of("taskId", String.valueOf(command.taskId()))));
        if (!"COUNTING".equals(task.status())) {
            throw new ProblemException("m5.count.not_counting");
        }
        List<SubmitCount.Line> lines = command.lines() == null ? List.of() : command.lines();
        Set<String> seen = new HashSet<>();
        for (SubmitCount.Line line : lines) {
            if (!valid(line) || !seen.add(key(line.batchId(), line.condition().name()))) {
                throw new ProblemException("m5.count.line_invalid");
            }
        }
        Map<UUID, UUID> skuOf = new HashMap<>();
        for (SubmitCount.Line line : lines) {
            BatchView batch = batches.getBatch(line.batchId(), scope)
                    .orElseThrow(() -> new ProblemException("m5.batch.not_found", Map.of("batchId", line.batchId())));
            if ("SKUS".equals(task.scopeKind()) && !task.skuIds().contains(batch.skuId())) {
                throw new ProblemException("m5.count.line_out_of_scope", Map.of("batchId", line.batchId()));
            }
            skuOf.put(line.batchId(), batch.skuId());
        }
        for (CountStore.Lot expected : store.expectation(task.taskId())) {
            if (!seen.contains(key(expected.batchId(), expected.condition()))) {
                throw new ProblemException("m5.count.line_missing", Map.of("batchId", expected.batchId()));
            }
        }

        Map<UUID, BigDecimal> averages = new LinkedHashMap<>();
        List<Movement> movements = new ArrayList<>();
        BigDecimal reviewValue = BigDecimal.ZERO.setScale(2);
        int reviewLines = 0;
        int lineNo = 0;
        for (SubmitCount.Line line : lines) {
            lineNo++;
            UUID lineId = Ids.next();
            UUID sku = skuOf.get(line.batchId());
            String condition = line.condition().name();
            BigDecimal book = store.onHand(task.locationId(), line.batchId(), condition);
            boolean skipped = line.countedQty() == null;
            BigDecimal variance = skipped ? BigDecimal.ZERO : line.countedQty().subtract(book);
            BigDecimal average = averages.computeIfAbsent(sku, s -> inventory
                    .entityAverageCost(s, scope)
                    .map(EntityCost::avgCost)
                    .orElse(BigDecimal.ZERO));
            BigDecimal value = variance.abs().multiply(average).setScale(2, RoundingMode.HALF_UP);
            boolean within = variance.signum() == 0 || policy.withinTolerance(variance, book, scope);
            jdbc.update(
                    """
                    insert into inventory.count_line
                        (line_id, task_id, owner_entity_id, location_id, line_no, batch_id, sku_id, condition,
                         expected_qty, counted_qty, skip_reason, variance_qty, unit_cost, variance_value,
                         within_tolerance)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    lineId,
                    task.taskId(),
                    task.ownerEntityId(),
                    task.locationId(),
                    lineNo,
                    line.batchId(),
                    sku,
                    condition,
                    book,
                    line.countedQty(),
                    skipped ? line.skipReason().strip() : null,
                    variance,
                    average,
                    value,
                    within);
            if (!within) {
                reviewLines++;
                reviewValue = reviewValue.add(value);
            } else if (variance.signum() != 0) {
                movements.add(new Movement(
                        task.locationId(),
                        line.batchId(),
                        line.condition(),
                        MovementType.COUNT_ADJUST,
                        variance,
                        null,
                        lineId));
            }
        }
        if (!movements.isEmpty()) {
            ledger.post(new PostMovements(task.taskId(), null, null, movements), scope);
        }
        boolean review = reviewLines > 0;
        Integer band = review ? policy.band(reviewValue, scope) : null;
        jdbc.update(
                """
                update inventory.count_task
                   set status = ?, outcome = ?, submitted_by = ?, submitted_at = ?, review_value = ?, review_band = ?
                 where task_id = ?
                """,
                review ? "VARIANCE_REVIEW" : "CLOSED",
                review ? null : "POSTED",
                scope.userId(),
                Timestamp.from(clock.instant()),
                review ? reviewValue : null,
                band,
                task.taskId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", review ? "VARIANCE_REVIEW" : "CLOSED");
        after.put("lines", lines.size());
        after.put("postedLines", movements.size());
        after.put("reviewLines", reviewLines);
        if (review) {
            after.put("reviewValue", reviewValue.toPlainString());
            after.put("band", band);
        }
        audit.record(
                AUDIT_SUBMITTED, Subject.of("count_task", task.taskId()), Map.of("status", "COUNTING"), after, scope);
        events.publish(new CountSubmitted(
                task.taskId(),
                task.ownerEntityId(),
                task.locationId(),
                lines.size(),
                movements.size(),
                reviewLines,
                review ? reviewValue : BigDecimal.ZERO.setScale(2),
                band));
        return task.taskId();
    }

    private static boolean valid(SubmitCount.Line line) {
        if (line == null || line.batchId() == null || line.condition() == null) {
            return false;
        }
        boolean counted = line.countedQty() != null;
        boolean skipped = line.skipReason() != null && !line.skipReason().isBlank();
        if (counted == skipped) {
            return false;
        }
        return !counted
                || (line.countedQty().signum() >= 0
                        && line.countedQty().stripTrailingZeros().scale() <= 3);
    }

    private static String key(UUID batchId, String condition) {
        return batchId + "/" + condition;
    }
}
