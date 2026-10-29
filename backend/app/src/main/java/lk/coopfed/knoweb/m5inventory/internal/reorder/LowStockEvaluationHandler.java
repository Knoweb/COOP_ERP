package lk.coopfed.knoweb.m5inventory.internal.reorder;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m5inventory.api.EvaluateLowStock;
import lk.coopfed.knoweb.m5inventory.api.LowStockDetected;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluates stock availability against configured reorder thresholds and publishes low-stock events (PGL-M2-01).
 *
 * <p>Implements deterministic deduplication and re-arming:
 * <ul>
 *   <li>When stock drops to or below {@code reorder_level} (including exact equality) and has not yet been alerted,
 *       publishes {@code inventory.low_stock_detected.v1} and flags {@code alert_triggered = true}.</li>
 *   <li>Subsequent sales or checks while remaining below the threshold do not emit duplicate alerts.</li>
 *   <li>When stock is replenished strictly above {@code reorder_level}, {@code alert_triggered} is reset to false,
 *       re-arming the control for future threshold crossings.</li>
 * </ul>
 */
@Service
@CommandHandler(permission = "inv.reorder.manage")
public class LowStockEvaluationHandler implements Handles<EvaluateLowStock, LowStockEvaluationResult> {

    public static final String AUDIT_LOW_STOCK_DETECTED = "LOW_STOCK_DETECTED";

    private final ReorderStore store;
    private final PartyQueries party;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    public LowStockEvaluationHandler(
            ReorderStore store,
            PartyQueries party,
            JdbcTemplate jdbc,
            Clock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.party = party;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public LowStockEvaluationResult handle(EvaluateLowStock command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        if (command.locationId() == null) {
            throw new ProblemException("m5.reorder.location_required");
        }
        if (party.getLocation(command.locationId(), scope)
                .filter(l -> scope.entityId().equals(l.ownerEntityId()))
                .isEmpty()) {
            throw new ProblemException("m5.location.not_in_scope", Map.of("locationId", command.locationId()));
        }
        if (scope.locationId() != null && !scope.locationId().equals(command.locationId())) {
            throw new ProblemException("m5.location.not_in_scope", Map.of("locationId", command.locationId()));
        }

        if (command.skuId() != null) {
            return evaluateSku(command.locationId(), command.skuId(), scope);
        }
        return evaluateLocation(command.locationId(), scope);
    }

    @Transactional
    public LowStockEvaluationResult evaluateSku(UUID locationId, UUID skuId, ScopeContext scope) {
        UUID ownerEntityId = scope.entityId();
        Optional<ReorderStore.ReorderControlRecord> recordOpt =
                store.findControlForUpdate(ownerEntityId, locationId, skuId);
        if (recordOpt.isEmpty() || !recordOpt.get().isActive()) {
            return new LowStockEvaluationResult(0, 0);
        }
        int alerts = evaluateRecord(recordOpt.get(), scope);
        return new LowStockEvaluationResult(1, alerts);
    }

    @Transactional
    public LowStockEvaluationResult evaluateLocation(UUID locationId, ScopeContext scope) {
        UUID ownerEntityId = scope.entityId();
        List<ReorderStore.ReorderControlRecord> records =
                store.findControlsAtLocationForUpdate(ownerEntityId, locationId);
        int alerts = 0;
        for (ReorderStore.ReorderControlRecord record : records) {
            alerts += evaluateRecord(record, scope);
        }
        return new LowStockEvaluationResult(records.size(), alerts);
    }

    private int evaluateRecord(ReorderStore.ReorderControlRecord record, ScopeContext scope) {
        Instant now = clock.instant();
        ReorderStore.StockQuantities stock = store.calculateStock(record.locationId(), record.skuId());
        boolean isLow = stock.available().compareTo(record.reorderLevel()) <= 0;

        if (isLow && !record.alertTriggered()) {
            // First threshold crossing / low stock detected -> alert and mark triggered
            jdbc.update(
                    """
                    update inventory.reorder_control
                       set alert_triggered = true,
                           last_alerted_at = ?,
                           last_alert_qty = ?,
                           updated_at = ?
                     where reorder_control_id = ?
                    """,
                    Timestamp.from(now),
                    stock.available(),
                    Timestamp.from(now),
                    record.reorderControlId());

            audit.record(
                    AUDIT_LOW_STOCK_DETECTED,
                    Subject.of("reorder_control", record.reorderControlId()),
                    null,
                    Map.of(
                            "locationId", record.locationId(),
                            "skuId", record.skuId(),
                            "onHandQty", stock.onHand(),
                            "availableQty", stock.available(),
                            "reorderLevel", record.reorderLevel(),
                            "reorderQty", record.reorderQty()),
                    scope);

            events.publish(new LowStockDetected(
                    record.reorderControlId(),
                    record.ownerEntityId(),
                    record.locationId(),
                    record.skuId(),
                    stock.onHand(),
                    stock.available(),
                    record.reorderLevel(),
                    record.reorderQty(),
                    record.maxQty(),
                    now));

            return 1;

        } else if (!isLow && record.alertTriggered()) {
            // Replenished strictly above reorder level -> re-arm the control
            jdbc.update(
                    """
                    update inventory.reorder_control
                       set alert_triggered = false,
                           updated_at = ?
                     where reorder_control_id = ?
                    """,
                    Timestamp.from(now),
                    record.reorderControlId());
            return 0;
        }

        // Already alerted and still low, or never alerted and normal -> deduplicate / no-op
        return 0;
    }
}
