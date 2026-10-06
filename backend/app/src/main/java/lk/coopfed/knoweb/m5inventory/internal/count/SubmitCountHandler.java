package lk.coopfed.knoweb.m5inventory.internal.count;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
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
import lk.coopfed.knoweb.m5inventory.internal.control.StockOnHand;
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
 * <p><b>The expectation during trading (doc 25 DR-8, E-06, refined in wave 2, M5-13).</b> A line
 * that carries {@code countedAt} (the form stamps each line as it is entered) is measured against
 * the lot as it stood at that moment: its quantity now less every movement that occurred after
 * (by {@code occurred_at}), the moment held between the start of the count and now. Selling the
 * item between counting the shelf and pressing submit is then no surplus. A line without it is
 * measured against the lot at submit, as before. Nothing is frozen either way.
 *
 * <p><b>What posts without a second person (wave 2, M5-14; {@code 2026-10-06-wave2-stock-approvals.md}
 * (5)).</b> A variance is within tolerance when it is within the quantity or percentage tolerance
 * (F-10) <b>and</b> its value within {@code inventory.count_tolerance_value}, and it is not a
 * surplus on a lot whose book held nothing; when the variances within tolerance add up to more
 * than {@code inventory.count_autopost_value_cap}, none posts and every variance waits. A line is
 * valued at the entity average, else at its lot's cost; a variance of no cost routes the review
 * to band 2.
 *
 * <p>Mutation: a count line per submitted line with its book, variance, value, whether it is
 * within tolerance and its {@code counted_at}; the variances within tolerance posted at once as
 * COUNT_ADJUST citing the count; the task VARIANCE_REVIEW with the value and band of the rest, or
 * CLOSED (POSTED) when there is no rest. Audit {@code COUNT_SUBMITTED}, and {@code
 * COUNT_TOLERANCE_CLEARED} (REVIEW) naming every variance posted within tolerance; event {@code
 * count.submitted.v1}.
 */
@Service
@CommandHandler(permission = "shop.count.record")
class SubmitCountHandler implements Handles<SubmitCount, UUID> {

    static final String AUDIT_SUBMITTED = "COUNT_SUBMITTED";
    static final String AUDIT_TOLERANCE_CLEARED = "COUNT_TOLERANCE_CLEARED";

    private final CountStore store;
    private final ControlPolicy policy;
    private final BatchQueries batches;
    private final StockOnHand stock;
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
            StockOnHand stock,
            StockLedger ledger,
            JdbcTemplate jdbc,
            Clock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.policy = policy;
        this.batches = batches;
        this.stock = stock;
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

        // ---- measure every line --------------------------------------------------------------
        Instant now = clock.instant();
        List<Measured> measured = new ArrayList<>();
        int lineNo = 0;
        for (SubmitCount.Line line : lines) {
            lineNo++;
            UUID sku = skuOf.get(line.batchId());
            String condition = line.condition().name();
            Instant countedAt = countedAt(line.countedAt(), task.startedAt(), now);
            BigDecimal book = countedAt == null
                    ? store.onHand(task.locationId(), line.batchId(), condition)
                    : store.onHandAt(task.locationId(), line.batchId(), condition, countedAt);
            boolean skipped = line.countedQty() == null;
            BigDecimal variance = skipped ? BigDecimal.ZERO : line.countedQty().subtract(book);
            BigDecimal unit = stock.unitValue(sku, task.locationId(), line.batchId(), condition, scope);
            BigDecimal value = variance.abs().multiply(unit).setScale(2, RoundingMode.HALF_UP);
            // wave 2, M5-14: within the quantity tolerance AND within the value tolerance, and never a
            // surplus on a lot whose book held nothing (stock from nothing is always looked at).
            boolean within = variance.signum() == 0
                    || (policy.withinTolerance(variance, book, scope)
                            && policy.withinValueTolerance(value, scope)
                            && !(variance.signum() > 0 && book.signum() <= 0));
            measured.add(new Measured(Ids.next(), lineNo, line, sku, book, variance, unit, value, within, countedAt));
        }
        // ... and the count as a whole: many small variances together may not post unreviewed.
        BigDecimal autoPosted = measured.stream()
                .filter(m -> m.within() && m.variance().signum() != 0)
                .map(Measured::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean overCap = autoPosted.compareTo(policy.autopostValueCap(scope)) > 0;

        // ---- write the lines, post what clears ------------------------------------------------
        List<Movement> movements = new ArrayList<>();
        List<Map<String, Object>> cleared = new ArrayList<>();
        BigDecimal reviewValue = BigDecimal.ZERO.setScale(2);
        int reviewLines = 0;
        boolean zeroCostLine = false;
        for (Measured m : measured) {
            boolean within = m.within() && !(overCap && m.variance().signum() != 0);
            jdbc.update(
                    """
                    insert into inventory.count_line
                        (line_id, task_id, owner_entity_id, location_id, line_no, batch_id, sku_id, condition,
                         expected_qty, counted_qty, skip_reason, variance_qty, unit_cost, variance_value,
                         within_tolerance, counted_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    m.lineId(),
                    task.taskId(),
                    task.ownerEntityId(),
                    task.locationId(),
                    m.lineNo(),
                    m.line().batchId(),
                    m.sku(),
                    m.line().condition().name(),
                    m.book(),
                    m.line().countedQty(),
                    m.line().countedQty() == null ? m.line().skipReason().strip() : null,
                    m.variance(),
                    m.unit(),
                    m.value(),
                    within,
                    m.countedAt() == null ? null : Timestamp.from(m.countedAt()));
            if (!within) {
                reviewLines++;
                reviewValue = reviewValue.add(m.value());
                zeroCostLine |= m.unit().signum() == 0;
            } else if (m.variance().signum() != 0) {
                movements.add(new Movement(
                        task.locationId(),
                        m.line().batchId(),
                        m.line().condition(),
                        MovementType.COUNT_ADJUST,
                        m.variance(),
                        null,
                        m.lineId()));
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("lineNo", m.lineNo());
                entry.put("batchId", m.line().batchId());
                entry.put("varianceQty", m.variance().toPlainString());
                entry.put("value", m.value().toPlainString());
                cleared.add(entry);
            }
        }
        if (!movements.isEmpty()) {
            ledger.post(new PostMovements(task.taskId(), null, null, movements), scope);
        }
        boolean review = reviewLines > 0;
        Integer band = review ? policy.band(reviewValue, zeroCostLine, scope) : null;
        jdbc.update(
                """
                update inventory.count_task
                   set status = ?, outcome = ?, submitted_by = ?, submitted_at = ?, review_value = ?, review_band = ?
                 where task_id = ?
                """,
                review ? "VARIANCE_REVIEW" : "CLOSED",
                review ? null : "POSTED",
                scope.userId(),
                Timestamp.from(now),
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
        if (!cleared.isEmpty()) {
            // wave 2, M5-14: every variance cleared by tolerance is on the exception report (doc 25
            // section 9.4), so a pattern of small "within tolerance" losses is seen.
            audit.record(
                    AUDIT_TOLERANCE_CLEARED,
                    Subject.of("count_task", task.taskId()),
                    null,
                    Map.of(
                            "lines",
                            cleared,
                            "value",
                            autoPosted.setScale(2, RoundingMode.HALF_UP).toPlainString()),
                    scope,
                    "Count variances posted within tolerance, without a second person");
        }
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

    /** One submitted line, measured. */
    private record Measured(
            UUID lineId,
            int lineNo,
            SubmitCount.Line line,
            UUID sku,
            BigDecimal book,
            BigDecimal variance,
            BigDecimal unit,
            BigDecimal value,
            boolean within,
            Instant countedAt) {}

    /**
     * The moment a line is measured at: the form's stamp, held between the start of the count and
     * now (a browser's clock may run a little early or late; before the start the count did not
     * exist, after now nothing has moved yet). Null when the line has none: the lot at submit.
     */
    private static Instant countedAt(Instant stamped, Instant startedAt, Instant now) {
        if (stamped == null) {
            return null;
        }
        Instant moment = startedAt != null && stamped.isBefore(startedAt) ? startedAt : stamped;
        return moment.isAfter(now) ? now : moment;
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
