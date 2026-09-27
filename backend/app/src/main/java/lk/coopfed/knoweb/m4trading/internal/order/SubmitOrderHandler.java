package lk.coopfed.knoweb.m4trading.internal.order;

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
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m4trading.api.OrderLineSummary;
import lk.coopfed.knoweb.m4trading.api.OrderSubmitted;
import lk.coopfed.knoweb.m4trading.api.SubmitOrder;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingDocuments;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import lk.coopfed.knoweb.m4trading.internal.document.TradingSeries;
import lk.coopfed.knoweb.m4trading.internal.queries.OrderStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SubmitOrder (24A section 6). Guards: the buyer's entity-wide OWN scope; an order the caller owns;
 * DRAFT; the relationship still ACTIVE; at least one line (the ORD validator). Mutation: the
 * buyer's ENTITY series registered if new ({@link TradingSeries}, 24B), the issuance (number, hash,
 * totals, DRAFT to ISSUED), then ISSUED to SUBMITTED (CR-24A-1 item 6). Audit ORDER_SUBMITTED; event
 * order.submitted.v1.
 */
@Service
@CommandHandler(permission = "ord.order.submit")
public class SubmitOrderHandler implements Handles<SubmitOrder, String> {

    static final String AUDIT_SUBMITTED = "ORDER_SUBMITTED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final DocumentIssuance issuance;
    private final TradingSeries series;
    private final OrderGuards guards;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    SubmitOrderHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            DocumentIssuance issuance,
            TradingSeries series,
            OrderGuards guards,
            TradingClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.issuance = issuance;
        this.series = series;
        this.guards = guards;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public String handle(SubmitOrder command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID orderId = TradingGuards.required(command.orderId(), "orderId");
        DocumentRecord draft = guards.ownOrder(orderId, scope);
        if (!OrderStatus.DRAFT.equals(draft.status())) {
            throw new ProblemException("m4.order.not_draft");
        }
        Map<String, Object> order = jdbc.queryForMap(
                "select relationship_id, seller_entity_id, requested_eta from trading.doc_order where document_id = ?",
                orderId);
        UUID relationshipId = (UUID) order.get("relationship_id");
        UUID seller = (UUID) order.get("seller_entity_id");
        guards.activeRelationship(relationshipId, scope);

        series.ensureEntitySeries(OrderTypeHandler.ORD, scope);
        DocumentRecord issued = issuance.issue(draft, documents.findLines(orderId), scope);
        documents.addStateTransition(
                clock.transition(orderId, TradingDocuments.ISSUED, OrderStatus.SUBMITTED, scope.userId(), null, null),
                scope);

        List<OrderLineSummary> lines = new ArrayList<>();
        for (DocumentLineRecord line : documents.findLines(orderId)) {
            lines.add(new OrderLineSummary(
                    line.id(), line.lineNo(), line.skuId(), line.uomCode(), line.qty(), null, null));
        }
        LocalDate eta = order.get("requested_eta") instanceof java.sql.Date d ? d.toLocalDate() : null;

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", OrderStatus.SUBMITTED);
        after.put("docNumber", issued.docNumberDisplay());
        after.put("netAmount", issued.netAmount());
        audit.record(AUDIT_SUBMITTED, Subject.of("order", orderId), Map.of("status", OrderStatus.DRAFT), after, scope);

        events.publish(new OrderSubmitted(
                orderId, issued.docNumberDisplay(), relationshipId, scope.entityId(), seller, eta, List.copyOf(lines)));
        return issued.docNumberDisplay();
    }
}
