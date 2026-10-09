package lk.coopfed.knoweb.m4trading.internal.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentIssuance;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.RelationshipView;
import lk.coopfed.knoweb.m4trading.api.AmendOrder;
import lk.coopfed.knoweb.m4trading.api.OrderAmended;
import lk.coopfed.knoweb.m4trading.api.OrderCancelled;
import lk.coopfed.knoweb.m4trading.api.OrderLineSummary;
import lk.coopfed.knoweb.m4trading.api.OrderSubmitted;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingDocuments;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import lk.coopfed.knoweb.m4trading.internal.document.TradingSeries;
import lk.coopfed.knoweb.m4trading.internal.queries.OrderStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AmendOrder (24A section 6: "buyer; before lock_at or override; not FULFILLED; version+1; lines
 * superseded; re-accept required"). Built for the order the seller has not yet decided, which is
 * all the demo needs: an accepted order is refused ({@code m4.order.not_amendable}), since its
 * allocation, lock time and exposure were taken on its lines (the lock-override path of 24A is not
 * built).
 *
 * <p>Guards, in order: the buyer's entity-wide OWN scope; an order the caller owns (locked);
 * DRAFT or SUBMITTED, with no decision of the seller ({@code m4.order.not_amendable}); the
 * relationship of the pair ACTIVE today; a requested ETA not in the past; the lines, as for
 * CreateOrder ({@link OrderLinePricer}).
 *
 * <p>Mutation: an issued order's lines are never edited, and the kernel's SUPERSEDES link is for
 * drafts only, so the amendment is a new order: the next version ({@code doc_order.version} + 1)
 * that names the one it amends ({@code amends_order_id}, V0007), with the same seller, delivery
 * point and relationship, the lines priced afresh. When the amended order was SUBMITTED the new
 * one is issued from the buyer's series and SUBMITTED at once (the seller accepts it afresh); a
 * DRAFT stays a draft. The amended order goes to CANCELLED with reason ORDER_AMENDED, its lines'
 * cancelled quantity set, as CancelOrder does.
 *
 * <p>Audit ORDER_AMENDED (before: the amended order; after: the new version). Events
 * order.amended.v1, order.cancelled.v1 of the amended order and, when submitted,
 * order.submitted.v1 of the new one.
 */
@Service
// Doc 24 section 3.1 (the AmendOrder row) and the catalogue ("Submit, amend and cancel the
// entity's own orders"): amending is covered by ord.order.submit (wave 3, M4-01).
@CommandHandler(permission = "ord.order.submit")
public class AmendOrderHandler implements Handles<AmendOrder, UUID> {

    static final String AUDIT_AMENDED = "ORDER_AMENDED";

    /** The reason code the amended order is cancelled with. */
    public static final String AMENDED_REASON = "ORDER_AMENDED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final DocumentIssuance issuance;
    private final TradingSeries series;
    private final OrderGuards guards;
    private final OrderLinePricer pricer;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    @SuppressWarnings("java:S107") // the collaborators of one handler specification
    AmendOrderHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            DocumentIssuance issuance,
            TradingSeries series,
            OrderGuards guards,
            OrderLinePricer pricer,
            TradingClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.issuance = issuance;
        this.series = series;
        this.guards = guards;
        this.pricer = pricer;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(AmendOrder command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID buyer = scope.entityId();
        UUID amendedId = TradingGuards.required(command.orderId(), "orderId");
        DocumentRecord amended = guards.ownOrder(amendedId, scope);
        String status = amended.status();
        if (!OrderStatus.DRAFT.equals(status) && !OrderStatus.SUBMITTED.equals(status)) {
            throw new ProblemException("m4.order.not_amendable", Map.of("status", status));
        }
        String reason = TradingGuards.required(command.reason(), "reason");
        // The seller's decision takes the same lock: read the allocation only once it is held
        // (wave 2, M4MONEY-06).
        OrderLocks.lock(jdbc, amendedId);
        List<String> decision = jdbc.queryForList(
                "select status from trading.order_allocation where order_id = ?", String.class, amendedId);
        if (!decision.isEmpty()) {
            throw new ProblemException("m4.order.not_amendable", Map.of("status", decision.get(0)));
        }
        BigDecimal fulfilled = jdbc.queryForObject(
                "select coalesce(sum(fulfilled_qty), 0) from trading.order_allocation_line where order_id = ?",
                BigDecimal.class,
                amendedId);
        if (fulfilled != null && fulfilled.signum() > 0) {
            throw new ProblemException("m4.order.dispatched");
        }
        Map<String, Object> order = jdbc.queryForMap(
                """
                select relationship_id, seller_entity_id, version, deliver_to_location_id, deliver_to_code,
                       deliver_to_name_en, deliver_to_name_si, deliver_to_name_ta, deliver_to_address
                  from trading.doc_order where document_id = ?
                """,
                amendedId);
        UUID seller = (UUID) order.get("seller_entity_id");
        RelationshipView relationship = guards.activeRelationship((UUID) order.get("relationship_id"), scope);
        LocalDate today = clock.today();
        if (command.requestedEta() != null && command.requestedEta().isBefore(today)) {
            throw new ProblemException("m4.order.eta_past");
        }
        UUID orderId = Ids.next();
        OrderLinePricer.Priced priced = pricer.price(orderId, command.lines(), relationship, buyer, today, scope);
        int version = ((Number) order.get("version")).intValue() + 1;

        // the new version: the kernel draft, doc_order naming the order it amends, its lines.
        documents.save(TradingDocuments.draft(
                orderId, OrderTypeHandler.ORD, buyer, seller, null, scope.userId(), null, command.notes()));
        documents.saveLines(orderId, priced.lines());
        jdbc.update(
                """
                insert into trading.doc_order (document_id, relationship_id, buyer_entity_id, seller_entity_id,
                    requested_eta, version, amends_order_id, deliver_to_location_id, deliver_to_code,
                    deliver_to_name_en, deliver_to_name_si, deliver_to_name_ta, deliver_to_address)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                orderId,
                relationship.relationshipId(),
                buyer,
                seller,
                command.requestedEta(),
                version,
                amendedId,
                order.get("deliver_to_location_id"),
                order.get("deliver_to_code"),
                order.get("deliver_to_name_en"),
                order.get("deliver_to_name_si"),
                order.get("deliver_to_name_ta"),
                order.get("deliver_to_address"));
        for (DocumentLineRecord line : priced.lines()) {
            jdbc.update(
                    "insert into trading.doc_order_line (line_id, document_id, requested_qty) values (?, ?, ?)",
                    line.id(),
                    orderId,
                    line.qty());
        }
        String docNumber = null;
        if (OrderStatus.SUBMITTED.equals(status)) {
            series.ensureEntitySeries(OrderTypeHandler.ORD, scope);
            DocumentRecord issued = issuance.issue(
                    documents.findByIdForUpdate(orderId).orElseThrow(), documents.findLines(orderId), scope);
            documents.addStateTransition(
                    clock.transition(
                            orderId, TradingDocuments.ISSUED, OrderStatus.SUBMITTED, scope.userId(), null, null),
                    scope);
            docNumber = issued.docNumberDisplay();
        }

        // the amended order: closed, as CancelOrder closes it. The code stays ORDER_AMENDED, so a
        // reader can tell an amendment from a cancellation; the buyer's words are the reason text
        // (wave 3, M4-02).
        documents.addStateTransition(
                clock.transition(amendedId, status, OrderStatus.CANCELLED, scope.userId(), AMENDED_REASON, reason),
                scope);
        jdbc.update("update trading.doc_order_line set cancelled_qty = requested_qty where document_id = ?", amendedId);

        List<OrderLineSummary> lines = new ArrayList<>(priced.summary());
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("orderId", amendedId);
        before.put("docNumber", amended.docNumberDisplay());
        before.put("status", status);
        before.put("version", version - 1);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("orderId", orderId);
        after.put("docNumber", docNumber);
        after.put("status", status);
        after.put("version", version);
        after.put("requestedEta", command.requestedEta());
        after.put("lines", lines.size());
        after.put("reason", reason);
        audit.record(AUDIT_AMENDED, Subject.of("order", orderId), before, after, scope, reason);

        events.publish(new OrderAmended(
                orderId,
                amendedId,
                version,
                docNumber,
                relationship.relationshipId(),
                buyer,
                seller,
                command.requestedEta(),
                status,
                reason,
                List.copyOf(lines)));
        events.publish(new OrderCancelled(
                amendedId, (UUID) order.get("relationship_id"), buyer, seller, buyer, AMENDED_REASON));
        if (docNumber != null) {
            events.publish(new OrderSubmitted(
                    orderId,
                    docNumber,
                    relationship.relationshipId(),
                    buyer,
                    seller,
                    command.requestedEta(),
                    List.copyOf(lines)));
        }
        return orderId;
    }
}
