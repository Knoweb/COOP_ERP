package lk.coopfed.knoweb.m4trading.internal.order;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m4trading.api.OrderRejected;
import lk.coopfed.knoweb.m4trading.api.RejectOrder;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import lk.coopfed.knoweb.m4trading.internal.queries.OrderStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RejectOrder (24A section 6), the seller's. Guards: the seller's entity-wide OWN scope; an order
 * placed with the caller; SUBMITTED and not yet decided; a reason. Mutation: the seller's
 * {@code order_allocation} row, REJECTED with the reason (CR-24A-1 item 2: the buyer's order is not
 * the seller's to change). Audit ORDER_REJECTED; event order.rejected.v1.
 */
@Service
@CommandHandler(permission = "ord.order.accept")
public class RejectOrderHandler implements Handles<RejectOrder, Void> {

    static final String AUDIT_REJECTED = "ORDER_REJECTED";

    private final JdbcTemplate jdbc;
    private final OrderGuards guards;
    private final AuditFacade audit;
    private final EventPublisher events;

    RejectOrderHandler(JdbcTemplate jdbc, OrderGuards guards, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.guards = guards;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Void handle(RejectOrder command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID orderId = TradingGuards.required(command.orderId(), "orderId");
        DocumentRecord order = guards.sellersOrder(orderId, scope);
        OrderDecision.requireUndecided(jdbc, order);
        String reason = TradingGuards.required(command.reasonCode(), "reasonCode");
        Map<String, Object> request = jdbc.queryForMap(
                "select relationship_id, buyer_entity_id from trading.doc_order where document_id = ?", orderId);
        UUID relationshipId = (UUID) request.get("relationship_id");
        UUID buyer = (UUID) request.get("buyer_entity_id");

        jdbc.update(
                """
                insert into trading.order_allocation (order_id, owner_entity_id, counterparty_entity_id,
                    relationship_id, status, reason_code, reason_text, decided_by)
                values (?, ?, ?, ?, 'REJECTED', ?, ?, ?)
                """,
                orderId,
                scope.entityId(),
                buyer,
                relationshipId,
                reason,
                command.reasonText(),
                scope.userId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", OrderStatus.REJECTED);
        after.put("reasonCode", reason);
        audit.record(
                AUDIT_REJECTED, Subject.of("order", orderId), Map.of("status", OrderStatus.SUBMITTED), after, scope);

        events.publish(new OrderRejected(orderId, relationshipId, buyer, scope.entityId(), reason));
        return null;
    }
}
