package lk.coopfed.knoweb.m4trading.internal.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m3pricing.query.AdjustmentTypeView;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.m4trading.api.OrderAdjusted;
import lk.coopfed.knoweb.m4trading.api.RecordOrderAdjustment;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RecordOrderAdjustment (PRC-10; FR-PRC-010; doc 24 §3.1, §6; doc 23 §3.2).
 * The seller records a manual adjustment (SPECIAL_DISCOUNT, DELIVERY_FEE) on an accepted order.
 * Guards in order:
 * 1. Scope is entity-wide OWN.
 * 2. Order exists and caller is the seller.
 * 3. Order is accepted and not cancelled.
 * 4. Adjustment of this type not already recorded on this order.
 * 5. Adjustment type exists, is active (M3).
 * 6. Caller holds the adjustment type's permission (e.g. prc.adjust.delivery / prc.adjust.special).
 * 7. Amount within bounds (positive, <= max_amount, <= max_percent of order value).
 */
@Service
@CommandHandler(permission = "ord.order.adjust")
public class RecordOrderAdjustmentHandler implements Handles<RecordOrderAdjustment, UUID> {

    static final String AUDIT_ADJUSTED = "ORDER_ADJUSTED";

    private final JdbcTemplate jdbc;
    private final OrderGuards guards;
    private final PricingQueries pricing;
    private final PermissionResolver permissions;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    RecordOrderAdjustmentHandler(
            JdbcTemplate jdbc,
            OrderGuards guards,
            PricingQueries pricing,
            PermissionResolver permissions,
            TradingClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.guards = guards;
        this.pricing = pricing;
        this.permissions = permissions;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RecordOrderAdjustment command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID orderId = TradingGuards.required(command.orderId(), "orderId");
        String typeCode = TradingGuards.required(command.adjustmentType(), "adjustmentType");
        BigDecimal amount = command.amount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ProblemException("m4.adjustment.amount_invalid");
        }

        DocumentRecord order = guards.sellersOrder(orderId, scope);
        if ("CANCELLED".equals(order.status())) {
            throw new ProblemException("m4.order.not_adjustable");
        }

        List<Map<String, Object>> allocRows =
                jdbc.queryForList("select status from trading.order_allocation where order_id = ?", orderId);
        if (allocRows.isEmpty() || !"ACCEPTED".equals(allocRows.get(0).get("status"))) {
            throw new ProblemException("m4.order.not_adjustable");
        }

        Integer existing = jdbc.queryForObject(
                "select count(*) from trading.doc_order_adjustment where order_id = ? and adjustment_type = ?",
                Integer.class,
                orderId,
                typeCode);
        if (existing != null && existing > 0) {
            throw new ProblemException("m4.adjustment.already_recorded");
        }

        AdjustmentTypeView type = pricing.getAdjustmentType(typeCode, scope)
                .filter(t -> "ACTIVE".equals(t.status()))
                .orElseThrow(() -> new ProblemException("m4.adjustment.type_unknown"));

        if (!permissions.allows(scope, type.permissionCode())) {
            throw new ProblemException("m4.adjustment.permission_denied");
        }

        if (type.maxAmount() != null && amount.compareTo(type.maxAmount()) > 0) {
            throw new ProblemException("m4.adjustment.exceeds_max_amount");
        }

        if (type.maxPercent() != null) {
            BigDecimal orderTotal = jdbc.queryForObject(
                    """
                    select coalesce(sum(allocated_qty * tier_price), 0)
                      from trading.order_allocation_line
                     where order_id = ?
                    """,
                    BigDecimal.class,
                    orderId);
            if (orderTotal == null || orderTotal.compareTo(BigDecimal.ZERO) <= 0) {
                orderTotal = order.netAmount() != null ? order.netAmount() : BigDecimal.ZERO;
            }
            BigDecimal maxAllowed =
                    orderTotal.multiply(type.maxPercent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            if (amount.compareTo(maxAllowed) > 0) {
                throw new ProblemException("m4.adjustment.exceeds_max_percent");
            }
        }

        UUID adjustmentId = Ids.next();
        Instant now = clock.now();
        jdbc.update(
                """
                insert into trading.doc_order_adjustment (
                    adjustment_id, order_id, owner_entity_id, counterparty_entity_id,
                    adjustment_type, kind, amount, description, recorded_by, recorded_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                adjustmentId,
                orderId,
                order.counterpartyEntityId(),
                order.ownerEntityId(),
                type.code(),
                type.kind().name(),
                amount,
                command.description(),
                scope.userId(),
                Timestamp.from(now));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("adjustmentId", adjustmentId);
        after.put("orderId", orderId);
        after.put("adjustmentType", type.code());
        after.put("kind", type.kind().name());
        after.put("amount", amount);
        if (command.description() != null) {
            after.put("description", command.description());
        }
        audit.record(AUDIT_ADJUSTED, Subject.of("order", orderId), null, after, scope);

        events.publish(new OrderAdjusted(
                adjustmentId,
                orderId,
                order.counterpartyEntityId(),
                order.ownerEntityId(),
                type.code(),
                type.kind().name(),
                amount,
                command.description(),
                now));

        return adjustmentId;
    }
}
