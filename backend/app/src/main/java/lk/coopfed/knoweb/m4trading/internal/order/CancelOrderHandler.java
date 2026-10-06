package lk.coopfed.knoweb.m4trading.internal.order;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m4trading.api.CancelOrder;
import lk.coopfed.knoweb.m4trading.api.OrderCancelled;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import lk.coopfed.knoweb.m4trading.internal.queries.OrderStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CancelOrder (24A section 6), the buyer's. Guards: the buyer's entity-wide OWN scope; its own
 * order; DRAFT or SUBMITTED and not rejected by the seller; nothing of it dispatched (no fulfilled
 * quantity on the seller's allocation); a reason. Mutation: the order to CANCELLED, every line's
 * cancelled quantity to its request. The demo cancels the whole order; cancelling the undispatched
 * remainder of a partly delivered order is deferred. Audit ORDER_CANCELLED; event
 * order.cancelled.v1.
 */
@Service
@CommandHandler(permission = "ord.order.submit")
public class CancelOrderHandler implements Handles<CancelOrder, Void> {

    static final String AUDIT_CANCELLED = "ORDER_CANCELLED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final OrderGuards guards;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    CancelOrderHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            OrderGuards guards,
            TradingClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.guards = guards;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Void handle(CancelOrder command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID orderId = TradingGuards.required(command.orderId(), "orderId");
        String reason = TradingGuards.required(command.reasonCode(), "reasonCode");
        DocumentRecord order = guards.ownOrder(orderId, scope);
        String status = order.status();
        if (!OrderStatus.DRAFT.equals(status) && !OrderStatus.SUBMITTED.equals(status)) {
            throw new ProblemException("m4.order.not_cancellable", Map.of("status", status));
        }
        // The seller's decision and its delivery note take the same lock: read the allocation and
        // what was dispatched only once it is held (wave 2, M4MONEY-06 and -07).
        OrderLocks.lock(jdbc, orderId);
        List<String> decision = jdbc.queryForList(
                "select status from trading.order_allocation where order_id = ?", String.class, orderId);
        if (decision.contains(OrderStatus.REJECTED)) {
            throw new ProblemException("m4.order.not_cancellable", Map.of("status", OrderStatus.REJECTED));
        }
        BigDecimal fulfilled = jdbc.queryForObject(
                "select coalesce(sum(fulfilled_qty), 0) from trading.order_allocation_line where order_id = ?",
                BigDecimal.class,
                orderId);
        if (fulfilled != null && fulfilled.signum() > 0) {
            throw new ProblemException("m4.order.dispatched");
        }

        documents.addStateTransition(
                clock.transition(orderId, status, OrderStatus.CANCELLED, scope.userId(), reason, command.reasonText()),
                scope);
        jdbc.update("update trading.doc_order_line set cancelled_qty = requested_qty where document_id = ?", orderId);
        Map<String, Object> ids = jdbc.queryForMap(
                "select relationship_id, seller_entity_id from trading.doc_order where document_id = ?", orderId);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", OrderStatus.CANCELLED);
        after.put("reasonCode", reason);
        audit.record(AUDIT_CANCELLED, Subject.of("order", orderId), Map.of("status", status), after, scope);

        events.publish(new OrderCancelled(
                orderId,
                (UUID) ids.get("relationship_id"),
                scope.entityId(),
                (UUID) ids.get("seller_entity_id"),
                scope.entityId(),
                reason));
        return null;
    }
}
