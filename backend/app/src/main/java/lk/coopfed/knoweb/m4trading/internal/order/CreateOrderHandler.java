package lk.coopfed.knoweb.m4trading.internal.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.RelationshipQueries;
import lk.coopfed.knoweb.m1party.query.RelationshipView;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m4trading.api.CreateOrder;
import lk.coopfed.knoweb.m4trading.api.OrderCreated;
import lk.coopfed.knoweb.m4trading.api.OrderLineSummary;
import lk.coopfed.knoweb.m4trading.api.TradePricing;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingDocuments;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CreateOrder (24A section 6). Guards, in order: the buyer's entity-wide OWN scope; a seller other
 * than the buyer; the ACTIVE relationship of the pair today (M1 LookupRelationship); a requested
 * ETA not in the past; at least one line; per line an item the buyer can see that is tradable, its
 * base unit (M2 publishes no conversion query yet; a demo deviation) and a positive quantity.
 * Mutation: the kernel draft (header and lines, the line priced at the indicative trade price of
 * the relationship, {@link TradePricing}), {@code doc_order} and {@code doc_order_line}. Audit
 * ORDER_CREATED; event order.created.v1 (CR-24A-1 item 7).
 */
@Service
@CommandHandler(permission = "ord.order.draft")
public class CreateOrderHandler implements Handles<CreateOrder, UUID> {

    static final String AUDIT_CREATED = "ORDER_CREATED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final RelationshipQueries relationships;
    private final CatalogueQueries catalogue;
    private final TradePricing pricing;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    CreateOrderHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            RelationshipQueries relationships,
            CatalogueQueries catalogue,
            TradePricing pricing,
            TradingClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.relationships = relationships;
        this.catalogue = catalogue;
        this.pricing = pricing;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(CreateOrder command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID buyer = scope.entityId();
        UUID seller = TradingGuards.required(command.sellerEntityId(), "sellerEntityId");
        if (seller.equals(buyer)) {
            throw new ProblemException("m4.order.seller_is_buyer");
        }
        LocalDate today = clock.today();
        RelationshipView relationship = relationships
                .lookupRelationship(seller, buyer, today, scope)
                .filter(row -> "ACTIVE".equals(row.status()))
                .orElseThrow(() -> new ProblemException("m4.order.relationship_inactive"));
        if (command.requestedEta() != null && command.requestedEta().isBefore(today)) {
            throw new ProblemException("m4.order.eta_past");
        }
        if (command.lines() == null || command.lines().isEmpty()) {
            throw new ProblemException("m4.order.lines_required");
        }

        UUID orderId = Ids.next();
        List<DocumentLineRecord> lines = new ArrayList<>();
        List<OrderLineSummary> summary = new ArrayList<>();
        int lineNo = 0;
        for (CreateOrder.Line line : command.lines()) {
            UUID skuId = TradingGuards.required(line.skuId(), "skuId");
            SkuView sku = catalogue
                    .getSku(skuId, scope)
                    .orElseThrow(() -> new ProblemException("m4.order.sku_not_found", Map.of("skuId", skuId)));
            if (!TradingGuards.tradable(sku)) {
                throw new ProblemException("m4.order.sku_not_active", Map.of("skuId", skuId));
            }
            String uom = line.uomCode() == null
                    ? sku.baseUomCode()
                    : line.uomCode().strip().toUpperCase();
            if (!uom.equals(sku.baseUomCode())) {
                throw new ProblemException("m4.order.uom_invalid", Map.of("skuId", skuId, "uomCode", uom));
            }
            TradingGuards.requirePositive(line.qty(), "m4.order.qty_not_positive", skuId);

            BigDecimal price = pricing.resolve(
                            relationship.relationshipId(), seller, buyer, skuId, uom, line.qty(), today, scope)
                    .map(TradePricing.TradePrice::unitPrice)
                    .orElse(null);
            BigDecimal total = price == null ? null : price.multiply(line.qty()).setScale(2, RoundingMode.HALF_UP);
            UUID lineId = Ids.next();
            lineNo++;
            lines.add(TradingDocuments.line(
                    lineId, orderId, lineNo, skuId, null, uom, line.qty(), price, null, null, total, null, null));
            summary.add(new OrderLineSummary(lineId, lineNo, skuId, uom, line.qty(), null, null));
        }

        documents.save(TradingDocuments.draft(
                orderId, OrderTypeHandler.ORD, buyer, seller, null, scope.userId(), null, command.notes()));
        documents.saveLines(orderId, lines);
        jdbc.update(
                """
                insert into trading.doc_order (document_id, relationship_id, buyer_entity_id, seller_entity_id,
                    requested_eta)
                values (?, ?, ?, ?, ?)
                """,
                orderId,
                relationship.relationshipId(),
                buyer,
                seller,
                command.requestedEta());
        for (DocumentLineRecord line : lines) {
            jdbc.update(
                    "insert into trading.doc_order_line (line_id, document_id, requested_qty) values (?, ?, ?)",
                    line.id(),
                    orderId,
                    line.qty());
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("orderId", orderId);
        after.put("relationshipId", relationship.relationshipId());
        after.put("sellerEntityId", seller);
        after.put("requestedEta", command.requestedEta());
        after.put("lines", summary.size());
        audit.record(AUDIT_CREATED, Subject.of("order", orderId), null, after, scope);

        events.publish(new OrderCreated(
                orderId, relationship.relationshipId(), buyer, seller, command.requestedEta(), List.copyOf(summary)));
        return orderId;
    }
}
