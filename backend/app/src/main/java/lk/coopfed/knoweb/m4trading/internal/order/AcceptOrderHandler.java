package lk.coopfed.knoweb.m4trading.internal.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.RelationshipView;
import lk.coopfed.knoweb.m4trading.api.AcceptOrder;
import lk.coopfed.knoweb.m4trading.api.ExposureWarning;
import lk.coopfed.knoweb.m4trading.api.InventoryAvailability;
import lk.coopfed.knoweb.m4trading.api.OrderAccepted;
import lk.coopfed.knoweb.m4trading.api.OrderAllocated;
import lk.coopfed.knoweb.m4trading.api.OrderLineSummary;
import lk.coopfed.knoweb.m4trading.api.TradePricing;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import lk.coopfed.knoweb.m4trading.internal.queries.ExposureCalculator;
import lk.coopfed.knoweb.m4trading.internal.queries.OrderStatus;
import lk.coopfed.knoweb.m4trading.query.ExposureView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AcceptOrder (24A section 6 and section 6.2), the seller's decision, recorded as the seller's own
 * rows (CR-24A-1 item 2). Guards, in order: the seller's entity-wide OWN scope; an order placed with
 * the caller; SUBMITTED and not yet decided; the relationship still ACTIVE; a committed ETA not in
 * the past; every override names a line of the order, carries a reason, and allocates no more than
 * the line's open request nor more than is available; every line has a trade price (M3).
 *
 * <p>The allocation (demo FCFS for one order): each line gets the lesser of its open request and
 * what {@link InventoryAvailability} says the seller has of the item; the snapshot is stored on the
 * run. Allocation across competing open orders is M5's reservation question and deferred. Lock time:
 * the ETA's start of day in the business zone less the relationship's order lock hours.
 *
 * <p>Mutation: {@code allocation_run}, {@code order_allocation} (ACCEPTED), one
 * {@code order_allocation_line} per line with the tier price at the ordered quantity. Audit
 * ORDER_ACCEPTED; events order.accepted.v1 and order.allocated.v1.
 *
 * <p>Exposure (M4-09): the buyer's exposure with the seller is computed before and after the
 * acceptance ({@link ExposureCalculator}); when the acceptance takes it across a threshold of the
 * relationship's credit limit (trading.exposure_warn_thresholds), exposure.warning.v1 is published
 * and the audit record carries the exposure. The order is accepted all the same: nothing blocks on
 * credit (doc 24 FR-BIL-080, ADR-12), so there is no HOLD state and no override to take.
 */
@Service
@CommandHandler(permission = "ord.order.accept")
public class AcceptOrderHandler implements Handles<AcceptOrder, UUID> {

    static final String AUDIT_ACCEPTED = "ORDER_ACCEPTED";
    static final String RULE_FCFS = "FCFS";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final OrderGuards guards;
    private final InventoryAvailability availability;
    private final TradePricing pricing;
    private final TradingClock clock;
    private final ExposureCalculator exposure;
    private final ObjectMapper json;
    private final AuditFacade audit;
    private final EventPublisher events;

    AcceptOrderHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            OrderGuards guards,
            InventoryAvailability availability,
            TradePricing pricing,
            TradingClock clock,
            ExposureCalculator exposure,
            ObjectMapper json,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.guards = guards;
        this.availability = availability;
        this.pricing = pricing;
        this.clock = clock;
        this.exposure = exposure;
        this.json = json;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(AcceptOrder command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID orderId = TradingGuards.required(command.orderId(), "orderId");
        DocumentRecord order = guards.sellersOrder(orderId, scope);
        OrderDecision.requireUndecided(jdbc, order);
        Map<String, Object> request = jdbc.queryForMap(
                "select relationship_id, buyer_entity_id from trading.doc_order where document_id = ?", orderId);
        UUID relationshipId = (UUID) request.get("relationship_id");
        UUID buyer = (UUID) request.get("buyer_entity_id");
        RelationshipView relationship = guards.activeRelationship(relationshipId, scope);
        LocalDate today = clock.today();
        LocalDate eta = TradingGuards.required(command.committedEta(), "committedEta");
        if (eta.isBefore(today)) {
            throw new ProblemException("m4.order.eta_past");
        }

        ExposureView before = exposure.exposure(relationship, scope);
        List<DocumentLineRecord> lines = documents.findLines(orderId);
        Map<UUID, BigDecimal> open = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "select line_id, requested_qty - cancelled_qty as open_qty from trading.doc_order_line"
                        + " where document_id = ?",
                orderId)) {
            open.put((UUID) row.get("line_id"), (BigDecimal) row.get("open_qty"));
        }
        Map<UUID, BigDecimal> available = availability.availability(
                scope.entityId(),
                lines.stream().map(DocumentLineRecord::skuId).distinct().toList(),
                scope);

        Map<UUID, AcceptOrder.LineOverride> overrides = new HashMap<>();
        if (command.overrides() != null) {
            for (AcceptOrder.LineOverride override : command.overrides()) {
                boolean known = lines.stream().anyMatch(line -> line.id().equals(override.lineId()));
                if (!known) {
                    throw new ProblemException(
                            "m4.order.line_unknown", Map.of("lineId", String.valueOf(override.lineId())));
                }
                TradingGuards.required(override.reason(), "reason");
                if (override.allocatedQty() == null || override.allocatedQty().signum() < 0) {
                    throw new ProblemException("m4.order.allocation_invalid", Map.of("lineId", override.lineId()));
                }
                overrides.put(override.lineId(), override);
            }
        }

        UUID runId = Ids.next();
        Instant lockAt = eta.atStartOfDay(clock.zone())
                .minusHours(relationship.orderLockHoursBeforeEta())
                .toInstant();
        List<OrderLineSummary> summary = new ArrayList<>();
        List<Object[]> allocationRows = new ArrayList<>();
        List<Map<String, Object>> overrideLog = new ArrayList<>();
        Map<UUID, BigDecimal> remaining = new HashMap<>(available);
        for (DocumentLineRecord line : lines) {
            BigDecimal openQty = open.getOrDefault(line.id(), line.qty());
            BigDecimal stock = remaining.getOrDefault(line.skuId(), BigDecimal.ZERO);
            AcceptOrder.LineOverride override = overrides.get(line.id());
            BigDecimal allocated;
            if (override != null) {
                allocated = override.allocatedQty();
                if (allocated.compareTo(openQty) > 0) {
                    throw new ProblemException("m4.order.allocation_exceeds_request", Map.of("lineId", line.id()));
                }
                if (allocated.compareTo(stock) > 0) {
                    throw new ProblemException("m4.order.allocation_exceeds_available", Map.of("lineId", line.id()));
                }
                overrideLog.add(Map.of("lineId", line.id(), "allocatedQty", allocated, "reason", override.reason()));
            } else {
                allocated = openQty.min(stock);
            }
            remaining.put(line.skuId(), stock.subtract(allocated));
            BigDecimal tierPrice = pricing.resolve(
                            relationshipId,
                            scope.entityId(),
                            buyer,
                            line.skuId(),
                            line.uomCode(),
                            line.qty(),
                            today,
                            scope)
                    .map(TradePricing.TradePrice::unitPrice)
                    .orElseThrow(() -> new ProblemException("m4.order.price_missing", Map.of("skuId", line.skuId())));
            allocationRows.add(
                    new Object[] {line.id(), allocated, tierPrice, override == null ? null : override.reason()});
            summary.add(new OrderLineSummary(
                    line.id(), line.lineNo(), line.skuId(), line.uomCode(), line.qty(), allocated, tierPrice));
        }

        jdbc.update(
                """
                insert into trading.allocation_run (run_id, owner_entity_id, rule, availability_snapshot, order_ids,
                    overrides, ran_by)
                values (?, ?, ?, cast(? as jsonb), ?, cast(? as jsonb), ?)
                """,
                runId,
                scope.entityId(),
                RULE_FCFS,
                write(available),
                new UUID[] {orderId},
                write(overrideLog),
                scope.userId());
        jdbc.update(
                """
                insert into trading.order_allocation (order_id, run_id, owner_entity_id, counterparty_entity_id,
                    relationship_id, status, committed_eta, lock_at, decided_by)
                values (?, ?, ?, ?, ?, 'ACCEPTED', ?, ?, ?)
                """,
                orderId,
                runId,
                scope.entityId(),
                buyer,
                relationshipId,
                eta,
                Timestamp.from(lockAt),
                scope.userId());
        for (Object[] row : allocationRows) {
            jdbc.update(
                    """
                    insert into trading.order_allocation_line (order_line_id, order_id, owner_entity_id,
                        counterparty_entity_id, allocated_qty, tier_price, override_reason)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """,
                    row[0],
                    orderId,
                    scope.entityId(),
                    buyer,
                    row[1],
                    row[2],
                    row[3]);
        }

        ExposureView now = exposure.exposure(relationship, scope);
        Integer crossed = now.warnThresholdPercent() != null
                        && (before.warnThresholdPercent() == null
                                || now.warnThresholdPercent() > before.warnThresholdPercent())
                ? now.warnThresholdPercent()
                : null;

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", OrderStatus.ACCEPTED);
        after.put("allocationRunId", runId);
        after.put("committedEta", eta);
        after.put("lockAt", lockAt);
        after.put("overrides", overrideLog.size());
        after.put("exposure", now.amount());
        if (crossed != null) {
            after.put("exposureWarningPercent", crossed);
        }
        audit.record(
                AUDIT_ACCEPTED, Subject.of("order", orderId), Map.of("status", OrderStatus.SUBMITTED), after, scope);

        events.publish(new OrderAccepted(
                orderId, relationshipId, buyer, scope.entityId(), runId, eta, lockAt, List.copyOf(summary)));
        events.publish(new OrderAllocated(orderId, runId, scope.entityId(), buyer, List.copyOf(summary)));
        if (crossed != null) {
            events.publish(new ExposureWarning(
                    relationshipId, scope.entityId(), buyer, now.amount(), now.creditLimit(), crossed, orderId));
        }
        return runId;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
