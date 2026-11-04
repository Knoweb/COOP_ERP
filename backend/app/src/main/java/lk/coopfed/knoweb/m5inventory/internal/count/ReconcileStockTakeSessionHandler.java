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
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.ReconcileStockTakeSession;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.StockTakeSessionReconciled;
import lk.coopfed.knoweb.m5inventory.api.SubmitCount;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import lk.coopfed.knoweb.m5inventory.internal.control.StockOnHand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ReconcileStockTakeSessionHandler (PGL-M2-09): aggregates counted mobile scans, applies uncounted handling,
 * measures variances against stock-at-count, auto-adjusts within tolerance or routes to VARIANCE_REVIEW.
 * Does not delegate to other @CommandHandler classes, ensuring compliance with CR-19A-6.
 */
@Service
@CommandHandler(permission = "shop.count.record")
class ReconcileStockTakeSessionHandler implements Handles<ReconcileStockTakeSession, UUID> {

    static final String AUDIT_RECONCILED = "STOCK_TAKE_SESSION_RECONCILED";
    static final String AUDIT_SUBMITTED = "COUNT_SUBMITTED";
    static final String AUDIT_TOLERANCE_CLEARED = "COUNT_TOLERANCE_CLEARED";

    private final CountStore countStore;
    private final StockTakeStore stockTakeStore;
    private final ControlPolicy policy;
    private final BatchQueries batches;
    private final StockOnHand stock;
    private final StockLedger ledger;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    ReconcileStockTakeSessionHandler(
            CountStore countStore,
            StockTakeStore stockTakeStore,
            ControlPolicy policy,
            BatchQueries batches,
            StockOnHand stock,
            StockLedger ledger,
            JdbcTemplate jdbc,
            Clock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.countStore = countStore;
        this.stockTakeStore = stockTakeStore;
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
    public UUID handle(ReconcileStockTakeSession command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        if (command.sessionId() == null) {
            throw new ProblemException("m5.count.not_found");
        }
        CountStore.Task task = countStore
                .lock(command.sessionId())
                .orElseThrow(() -> new ProblemException(
                        "m5.count.not_found",
                        Map.of("taskId", command.sessionId().toString())));
        if (!"COUNTING".equals(task.status())) {
            throw new ProblemException("m5.count.not_counting", Map.of("status", task.status()));
        }
        policy.requireEntityLocation(task.locationId(), scope);

        String uncounted = command.uncountedHandling() == null
                ? "ZERO"
                : command.uncountedHandling().trim().toUpperCase();
        if (!"ZERO".equals(uncounted) && !"SKIP".equals(uncounted)) {
            throw new ProblemException("m5.stock_take.uncounted_handling_invalid", Map.of("handling", uncounted));
        }
        if ("SKIP".equals(uncounted)
                && (command.skipReason() == null || command.skipReason().isBlank())) {
            throw new ProblemException("m5.stock_take.skip_reason_required");
        }

        List<CountStore.Lot> expectations = countStore.expectation(task.taskId());
        List<StockTakeStore.AggregatedLotCount> scans = stockTakeStore.aggregatedCounts(task.taskId());

        Map<String, StockTakeStore.AggregatedLotCount> scannedByLot = new HashMap<>();
        for (StockTakeStore.AggregatedLotCount scan : scans) {
            scannedByLot.put(key(scan.batchId(), scan.condition()), scan);
        }

        List<SubmitCount.Line> submitLines = new ArrayList<>();
        Set<String> processedKeys = new HashSet<>();

        // 1. Process all expected lots
        for (CountStore.Lot exp : expectations) {
            String lotKey = key(exp.batchId(), exp.condition());
            processedKeys.add(lotKey);
            StockTakeStore.AggregatedLotCount scan = scannedByLot.get(lotKey);

            if (scan != null) {
                submitLines.add(new SubmitCount.Line(
                        exp.batchId(),
                        LotCondition.valueOf(exp.condition()),
                        scan.totalQty(),
                        null,
                        scan.lastScannedAt()));
            } else if ("SKIP".equals(uncounted)) {
                submitLines.add(new SubmitCount.Line(
                        exp.batchId(),
                        LotCondition.valueOf(exp.condition()),
                        null,
                        command.skipReason().trim(),
                        clock.instant()));
            } else {
                submitLines.add(new SubmitCount.Line(
                        exp.batchId(), LotCondition.valueOf(exp.condition()), BigDecimal.ZERO, null, clock.instant()));
            }
        }

        // 2. Process unexpected lots (shelf surprises found during scanning)
        for (StockTakeStore.AggregatedLotCount scan : scans) {
            String lotKey = key(scan.batchId(), scan.condition());
            if (!processedKeys.contains(lotKey)) {
                submitLines.add(new SubmitCount.Line(
                        scan.batchId(),
                        LotCondition.valueOf(scan.condition()),
                        scan.totalQty(),
                        null,
                        scan.lastScannedAt()));
            }
        }

        // 3. Resolve SKU for each line
        Map<UUID, UUID> skuOf = new HashMap<>();
        for (SubmitCount.Line line : submitLines) {
            BatchView batch = batches.getBatch(line.batchId(), scope)
                    .orElseThrow(() -> new ProblemException("m5.batch.not_found", Map.of("batchId", line.batchId())));
            if ("SKUS".equals(task.scopeKind()) && !task.skuIds().contains(batch.skuId())) {
                throw new ProblemException("m5.count.line_out_of_scope", Map.of("batchId", line.batchId()));
            }
            skuOf.put(line.batchId(), batch.skuId());
        }

        // 4. Measure every line against book on hand
        Instant now = clock.instant();
        List<MeasuredLine> measured = new ArrayList<>();
        int lineNo = 0;
        for (SubmitCount.Line line : submitLines) {
            lineNo++;
            UUID sku = skuOf.get(line.batchId());
            String condition = line.condition().name();
            Instant countedAt = line.countedAt();
            BigDecimal book = countedAt == null
                    ? countStore.onHand(task.locationId(), line.batchId(), condition)
                    : countStore.onHandAt(task.locationId(), line.batchId(), condition, countedAt);
            if (book == null) {
                book = BigDecimal.ZERO;
            }
            boolean skipped = line.countedQty() == null;
            BigDecimal variance = skipped ? BigDecimal.ZERO : line.countedQty().subtract(book);
            BigDecimal unit = stock.unitValue(sku, task.locationId(), line.batchId(), condition, scope);
            BigDecimal value = variance.abs().multiply(unit).setScale(2, RoundingMode.HALF_UP);
            boolean within = variance.signum() == 0
                    || (policy.withinTolerance(variance, book, scope)
                            && policy.withinValueTolerance(value, scope)
                            && !(variance.signum() > 0 && book.signum() <= 0));
            measured.add(
                    new MeasuredLine(Ids.next(), lineNo, line, sku, book, variance, unit, value, within, countedAt));
        }

        // 5. Cap auto-post value across the count
        BigDecimal autoPosted = measured.stream()
                .filter(m -> m.within() && m.variance().signum() != 0)
                .map(MeasuredLine::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean overCap = autoPosted.compareTo(policy.autopostValueCap(scope)) > 0;

        // 6. Insert lines into count_line and determine posted movements
        List<Movement> movements = new ArrayList<>();
        List<Map<String, Object>> cleared = new ArrayList<>();
        BigDecimal reviewValue = BigDecimal.ZERO.setScale(2);
        int reviewLines = 0;
        boolean zeroCostLine = false;

        for (MeasuredLine m : measured) {
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

        // 7. Post ledger movements for auto-cleared lines (LedgerService is CommandHandler.INTERNAL)
        if (!movements.isEmpty()) {
            ledger.post(new PostMovements(task.taskId(), null, null, movements), scope);
        }

        // 8. Update task status (VARIANCE_REVIEW or CLOSED)
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

        // 9. Transition linked open expiry tasks to COUNTED
        jdbc.update(
                """
                update inventory.expiry_task
                   set status = 'COUNTED'
                 where count_task_id = ?
                   and status = 'OPEN'
                """,
                task.taskId());

        // 10. Audit facts
        Map<String, Object> afterCount = new LinkedHashMap<>();
        afterCount.put("status", review ? "VARIANCE_REVIEW" : "CLOSED");
        afterCount.put("lines", submitLines.size());
        afterCount.put("postedLines", movements.size());
        afterCount.put("reviewLines", reviewLines);
        if (review) {
            afterCount.put("reviewValue", reviewValue.toPlainString());
            afterCount.put("band", band);
        }
        audit.record(
                AUDIT_SUBMITTED,
                Subject.of("count_task", task.taskId()),
                Map.of("status", "COUNTING"),
                afterCount,
                scope);

        if (!cleared.isEmpty()) {
            audit.record(
                    AUDIT_TOLERANCE_CLEARED,
                    Subject.of("count_task", task.taskId()),
                    null,
                    Map.of("clearedLines", cleared.size(), "lines", cleared),
                    scope);
        }

        audit.record(
                AUDIT_RECONCILED,
                Subject.of("stock_take_session", task.taskId()),
                Map.of("status", "COUNTING"),
                Map.of(
                        "status",
                        review ? "VARIANCE_REVIEW" : "CLOSED",
                        "outcome",
                        review ? "REVIEW_REQUIRED" : "POSTED",
                        "uncountedHandling",
                        uncounted,
                        "linesCount",
                        submitLines.size(),
                        "reviewValue",
                        reviewValue.toPlainString(),
                        "reviewBand",
                        band != null ? band : 0),
                scope);

        // 11. Domain Events
        events.publish(new CountSubmitted(
                task.taskId(),
                task.ownerEntityId(),
                task.locationId(),
                submitLines.size(),
                movements.size(),
                reviewLines,
                review ? reviewValue : null,
                band));

        events.publish(new StockTakeSessionReconciled(
                task.taskId(),
                task.ownerEntityId(),
                task.locationId(),
                submitLines.size(),
                movements.size(),
                reviewLines,
                reviewValue,
                band));

        return task.taskId();
    }

    private static String key(UUID batchId, String condition) {
        return batchId + "/" + condition;
    }

    private record MeasuredLine(
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
}
