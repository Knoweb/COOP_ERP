package lk.coopfed.knoweb.m5inventory.internal.expiry;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.EvaluateExpiryAlerts;
import lk.coopfed.knoweb.m5inventory.api.ExpiryAlertScanResult;
import lk.coopfed.knoweb.m5inventory.api.NearExpiryDetected;
import lk.coopfed.knoweb.m5inventory.api.SlowMovingDetected;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import lk.coopfed.knoweb.m5inventory.query.NearExpiryLotView;
import lk.coopfed.knoweb.m5inventory.query.SlowMovingStockView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluates near-expiry stock and slow-moving items at a location, publishing alert facts with deterministic deduplication (PGL-M2-07).
 */
@Service
@CommandHandler(permission = "inv.expiry.scan")
public class EvaluateExpiryAlertsHandler implements Handles<EvaluateExpiryAlerts, ExpiryAlertScanResult> {

    public static final String AUDIT_NEAR_EXPIRY = "NEAR_EXPIRY_ALERT_TRIGGERED";
    public static final String AUDIT_SLOW_MOVING = "SLOW_MOVING_ALERT_TRIGGERED";

    private final ExpiryAlertStore store;
    private final ControlPolicy policy;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId businessZone;
    private final AuditFacade audit;
    private final EventPublisher events;

    public EvaluateExpiryAlertsHandler(
            ExpiryAlertStore store,
            ControlPolicy policy,
            JdbcTemplate jdbc,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.policy = policy;
        this.jdbc = jdbc;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public ExpiryAlertScanResult handle(EvaluateExpiryAlerts command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        if (command.locationId() == null) {
            throw new ProblemException("m5.expiry.location_required");
        }
        policy.requireEntityLocation(command.locationId(), scope);
        if (scope.locationId() != null && !scope.locationId().equals(command.locationId())) {
            throw new ProblemException("m5.location.not_in_scope", Map.of("locationId", command.locationId()));
        }

        return evaluateLocation(command.locationId(), scope);
    }

    @Transactional
    public ExpiryAlertScanResult evaluateLocation(UUID locationId, ScopeContext scope) {
        Instant now = clock.instant();
        Timestamp nowTs = Timestamp.from(now);
        LocalDate today = LocalDate.ofInstant(now, businessZone);

        int criticalDays = policy.nearExpiryCriticalDays(scope);
        int warningDays = policy.expiryWarningDays(scope);
        int slowMovingDays = policy.slowMovingDays(scope);
        int thresholdDos = policy.slowMovingThresholdDos(scope);

        int nearExpiryAlertsEmitted = 0;
        int slowMovingAlertsEmitted = 0;
        int totalEvaluated = 0;

        // 1. Evaluate near-expiry lots within the warning window
        List<NearExpiryLotView> nearExpiryLots =
                store.findNearExpiryLots(locationId, warningDays, null, today, criticalDays, warningDays, scope);
        totalEvaluated += nearExpiryLots.size();

        List<ExpiryAlertStore.ActiveAlertRecord> activeNearExpiry =
                store.findActiveAlerts(locationId, "NEAR_EXPIRY", scope);
        Map<UUID, ExpiryAlertStore.ActiveAlertRecord> activeExpiryByBatch = new HashMap<>();
        for (ExpiryAlertStore.ActiveAlertRecord record : activeNearExpiry) {
            if (record.batchId() != null) {
                activeExpiryByBatch.put(record.batchId(), record);
            }
        }

        for (NearExpiryLotView lot : nearExpiryLots) {
            if ("EXPIRED".equalsIgnoreCase(lot.urgencyTier())) {
                continue; // Already expired lots are handled by write-off, not alert feed
            }

            ExpiryAlertStore.ActiveAlertRecord existing = activeExpiryByBatch.get(lot.batchId());
            boolean emit = false;
            UUID alertId;

            if (existing == null) {
                // New near-expiry alert
                alertId = UUID.randomUUID();
                recordAlertTriggered(
                        alertId,
                        scope.entityId(),
                        locationId,
                        "NEAR_EXPIRY",
                        lot.skuId(),
                        lot.batchId(),
                        lot.urgencyTier(),
                        lot.qtyOnHand(),
                        nowTs);
                emit = true;
            } else if ("CRITICAL".equalsIgnoreCase(lot.urgencyTier())
                    && !"CRITICAL".equalsIgnoreCase(existing.alertTier())) {
                // Escalation from WARNING to CRITICAL: re-alert with higher urgency
                alertId = existing.alertId();
                updateAlertTier(alertId, "CRITICAL", lot.qtyOnHand(), nowTs);
                emit = true;
            } else {
                alertId = existing.alertId();
            }

            if (emit) {
                audit.record(
                        AUDIT_NEAR_EXPIRY,
                        Subject.of("location", locationId),
                        null,
                        Map.of(
                                "skuId", lot.skuId().toString(),
                                "batchId", lot.batchId().toString(),
                                "tier", lot.urgencyTier(),
                                "daysToExpiry", String.valueOf(lot.daysToExpiry()),
                                "qtyOnHand", lot.qtyOnHand().toPlainString()),
                        scope);

                events.publish(new NearExpiryDetected(
                        alertId,
                        scope.entityId(),
                        locationId,
                        lot.batchId(),
                        lot.batchNo(),
                        lot.skuId(),
                        lot.expiryDate(),
                        lot.daysToExpiry(),
                        lot.qtyOnHand(),
                        lot.urgencyTier(),
                        now));

                nearExpiryAlertsEmitted++;
            }
        }

        // Clean up any near-expiry alerts for batches that have since been depleted
        resolveObsoleteNearExpiryAlerts(locationId, nowTs);

        // 2. Evaluate slow-moving stock
        List<SlowMovingStockView> slowMovingItems =
                store.findSlowMovingStock(locationId, slowMovingDays, thresholdDos, today, businessZone, scope);
        totalEvaluated += slowMovingItems.size();

        List<ExpiryAlertStore.ActiveAlertRecord> activeSlowMoving =
                store.findActiveAlerts(locationId, "SLOW_MOVING", scope);
        Map<UUID, ExpiryAlertStore.ActiveAlertRecord> activeSlowBySku = new HashMap<>();
        for (ExpiryAlertStore.ActiveAlertRecord record : activeSlowMoving) {
            activeSlowBySku.put(record.skuId(), record);
        }

        Set<UUID> currentSlowSkus = new HashSet<>();

        for (SlowMovingStockView item : slowMovingItems) {
            currentSlowSkus.add(item.skuId());
            ExpiryAlertStore.ActiveAlertRecord existing = activeSlowBySku.get(item.skuId());

            if (existing == null) {
                UUID alertId = UUID.randomUUID();
                recordAlertTriggered(
                        alertId,
                        scope.entityId(),
                        locationId,
                        "SLOW_MOVING",
                        item.skuId(),
                        null,
                        item.classification(),
                        item.qtyOnHand(),
                        nowTs);

                audit.record(
                        AUDIT_SLOW_MOVING,
                        Subject.of("location", locationId),
                        null,
                        Map.of(
                                "skuId", item.skuId().toString(),
                                "classification", item.classification(),
                                "daysWithoutSale", String.valueOf(item.daysWithoutSale()),
                                "daysOfSupply", item.daysOfSupply().toPlainString(),
                                "qtyOnHand", item.qtyOnHand().toPlainString()),
                        scope);

                events.publish(new SlowMovingDetected(
                        alertId,
                        scope.entityId(),
                        locationId,
                        item.skuId(),
                        item.qtyOnHand(),
                        item.daysWithoutSale(),
                        item.lastSaleDate(),
                        item.salesInWindow(),
                        item.averageDailySales(),
                        item.daysOfSupply(),
                        item.classification(),
                        now));

                slowMovingAlertsEmitted++;
            }
        }

        // Clean up slow-moving alerts for items that now have sales or zero stock
        resolveObsoleteSlowMovingAlerts(locationId, currentSlowSkus, nowTs, scope);

        return new ExpiryAlertScanResult(nearExpiryAlertsEmitted, slowMovingAlertsEmitted, totalEvaluated);
    }

    private void recordAlertTriggered(
            UUID alertId,
            UUID entityId,
            UUID locationId,
            String alertType,
            UUID skuId,
            UUID batchId,
            String alertTier,
            BigDecimal qtyOnHand,
            Timestamp now) {
        String sql =
                """
                insert into inventory.stock_alert_state
                    (alert_id, owner_entity_id, location_id, alert_type, sku_id, batch_id,
                     alert_tier, status, qty_on_hand, last_alerted_at)
                values (?, ?, ?, ?, ?, ?, ?, 'TRIGGERED', ?, ?)
                """;
        jdbc.update(sql, alertId, entityId, locationId, alertType, skuId, batchId, alertTier, qtyOnHand, now);
    }

    private void updateAlertTier(UUID alertId, String newTier, BigDecimal qtyOnHand, Timestamp now) {
        String sql =
                """
                update inventory.stock_alert_state
                   set alert_tier = ?,
                       qty_on_hand = ?,
                       last_alerted_at = ?
                 where alert_id = ?
                """;
        jdbc.update(sql, newTier, qtyOnHand, now, alertId);
    }

    private int resolveObsoleteNearExpiryAlerts(UUID locationId, Timestamp now) {
        String sql =
                """
                update inventory.stock_alert_state a
                   set status = 'RESOLVED',
                       resolved_at = ?
                 where a.location_id = ?
                   and a.alert_type = 'NEAR_EXPIRY'
                   and a.status = 'TRIGGERED'
                   and not exists (
                       select 1
                         from inventory.stock_lot l
                        where l.location_id = a.location_id
                          and l.batch_id = a.batch_id
                          and l.qty_on_hand > 0
                   )
                """;
        return jdbc.update(sql, now, locationId);
    }

    private int resolveObsoleteSlowMovingAlerts(
            UUID locationId, Set<UUID> currentlySlowSkus, Timestamp now, ScopeContext scope) {
        if (currentlySlowSkus == null || currentlySlowSkus.isEmpty()) {
            return jdbc.update(
                    """
                    update inventory.stock_alert_state
                       set status = 'RESOLVED',
                           resolved_at = ?
                     where location_id = ?
                       and alert_type = 'SLOW_MOVING'
                       and status = 'TRIGGERED'
                    """,
                    now,
                    locationId);
        }

        List<ExpiryAlertStore.ActiveAlertRecord> active = store.findActiveAlerts(locationId, "SLOW_MOVING", scope);
        int resolved = 0;
        for (ExpiryAlertStore.ActiveAlertRecord record : active) {
            if (!currentlySlowSkus.contains(record.skuId())) {
                jdbc.update(
                        "update inventory.stock_alert_state set status = 'RESOLVED', resolved_at = ? where alert_id = ?",
                        now,
                        record.alertId());
                resolved++;
            }
        }
        return resolved;
    }
}
