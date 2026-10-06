package lk.coopfed.knoweb.m4trading.internal.order;

import java.time.LocalDate;
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
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m1party.query.RelationshipQueries;
import lk.coopfed.knoweb.m1party.query.RelationshipView;
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
 * ETA not in the past; a delivery location, when named, that is one of the buyer's own (M4-11,
 * CR-24A-2); at least one line; per line an item the buyer can see that is tradable, its
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
    private final PartyQueries parties;
    private final OrderLinePricer pricer;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    CreateOrderHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            RelationshipQueries relationships,
            PartyQueries parties,
            OrderLinePricer pricer,
            TradingClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.relationships = relationships;
        this.parties = parties;
        this.pricer = pricer;
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
        UUID deliverTo = command.deliverToLocationId();
        // The location is read here, in the buyer's session, and its code, names and address are
        // kept on the order (V0004): the seller may not read the buyer's locations, and reads the
        // delivery point from the order instead (CR-24A-2 as revised, 28 September 2026).
        LocationView deliverToLocation = null;
        if (deliverTo != null) {
            deliverToLocation = parties.getLocation(deliverTo, scope)
                    .filter(location -> buyer.equals(location.ownerEntityId()))
                    .orElseThrow(() -> new ProblemException("m4.order.deliver_to_unknown"));
        }
        UUID orderId = Ids.next();
        OrderLinePricer.Priced priced = pricer.price(orderId, command.lines(), relationship, buyer, today, scope);
        List<DocumentLineRecord> lines = priced.lines();
        List<OrderLineSummary> summary = priced.summary();

        documents.save(TradingDocuments.draft(
                orderId, OrderTypeHandler.ORD, buyer, seller, null, scope.userId(), null, command.notes()));
        documents.saveLines(orderId, lines);
        jdbc.update(
                """
                insert into trading.doc_order (document_id, relationship_id, buyer_entity_id, seller_entity_id,
                    requested_eta, deliver_to_location_id, deliver_to_code, deliver_to_name_en, deliver_to_name_si,
                    deliver_to_name_ta, deliver_to_address)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                orderId,
                relationship.relationshipId(),
                buyer,
                seller,
                command.requestedEta(),
                deliverTo,
                deliverToLocation == null ? null : deliverToLocation.locationCode(),
                deliverToLocation == null ? null : deliverToLocation.nameEn(),
                deliverToLocation == null ? null : deliverToLocation.nameSi(),
                deliverToLocation == null ? null : deliverToLocation.nameTa(),
                deliverToLocation == null ? null : deliverToLocation.address());
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
        after.put("deliverToLocationId", deliverTo);
        after.put("lines", summary.size());
        audit.record(AUDIT_CREATED, Subject.of("order", orderId), null, after, scope);

        events.publish(new OrderCreated(
                orderId, relationship.relationshipId(), buyer, seller, command.requestedEta(), List.copyOf(summary)));
        return orderId;
    }
}
