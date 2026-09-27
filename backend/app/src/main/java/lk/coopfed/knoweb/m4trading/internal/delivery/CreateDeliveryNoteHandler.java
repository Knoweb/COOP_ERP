package lk.coopfed.knoweb.m4trading.internal.delivery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import lk.coopfed.knoweb.m4trading.api.CreateDeliveryNote;
import lk.coopfed.knoweb.m4trading.api.DeliveryNoteCreated;
import lk.coopfed.knoweb.m4trading.internal.delivery.AllocatedLines.AllocatedLine;
import lk.coopfed.knoweb.m4trading.internal.document.TradingDocuments;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CreateDeliveryNote (24A section 6). Guards, in order: the seller's entity-wide OWN scope; at
 * least one drop, each with a ship-to shop, a bill-to entity and at least one line; one buyer per
 * note (the kernel document has one counterparty; a demo deviation); per line an order line of an
 * order the caller accepted and the buyer has not cancelled, billed to its buyer, with a positive
 * quantity; the note's lines of one order line together no more than its allocated and not yet
 * dispatched quantity. That the ship-to shop belongs to the bill-to entity is not checked here: the
 * seller cannot read the buyer's locations (m1party own_read), so the receiver's GRN, captured at
 * one of its own shops, is where a wrong ship-to shows.
 *
 * <p>Mutation: the kernel draft (one line per delivered line at the order's tier price, the
 * order line as its reference line), {@code doc_delivery}, {@code doc_delivery_drop},
 * {@code doc_delivery_line}. Audit DN_CREATED; event delivery_note.created.v1.
 */
@Service
@CommandHandler(permission = "del.note.draft")
public class CreateDeliveryNoteHandler implements Handles<CreateDeliveryNote, UUID> {

    static final String AUDIT_CREATED = "DN_CREATED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final AllocatedLines allocated;
    private final DeliveryReads reads;
    private final AuditFacade audit;
    private final EventPublisher events;

    CreateDeliveryNoteHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            AllocatedLines allocated,
            DeliveryReads reads,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.allocated = allocated;
        this.reads = reads;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(CreateDeliveryNote command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID seller = scope.entityId();
        if (command.drops() == null || command.drops().isEmpty()) {
            throw new ProblemException("m4.delivery.drops_required");
        }
        UUID buyer = TradingGuards.required(command.drops().get(0).billToEntityId(), "billToEntityId");

        UUID noteId = Ids.next();
        List<DocumentLineRecord> kernelLines = new ArrayList<>();
        List<Object[]> dropRows = new ArrayList<>();
        List<Object[]> lineRows = new ArrayList<>();
        Map<UUID, BigDecimal> onThisNote = new HashMap<>();
        Set<UUID> allOrders = new LinkedHashSet<>();
        int seq = 0;
        int lineNo = 0;
        for (CreateDeliveryNote.Drop drop : command.drops()) {
            UUID shipTo = TradingGuards.required(drop.shipToLocationId(), "shipToLocationId");
            UUID billTo = TradingGuards.required(drop.billToEntityId(), "billToEntityId");
            if (!billTo.equals(buyer)) {
                throw new ProblemException("m4.delivery.one_buyer");
            }
            if (drop.lines() == null || drop.lines().isEmpty()) {
                throw new ProblemException("m4.delivery.lines_required");
            }
            UUID dropId = Ids.next();
            Set<UUID> dropOrders = new LinkedHashSet<>();
            seq++;
            for (CreateDeliveryNote.Line line : drop.lines()) {
                UUID orderLineId = TradingGuards.required(line.orderLineId(), "orderLineId");
                AllocatedLine source = allocated.find(orderLineId, seller, false);
                if (!source.buyerEntityId().equals(billTo)) {
                    throw new ProblemException("m4.delivery.bill_to_mismatch", Map.of("orderId", source.orderId()));
                }
                if (line.qty() == null || line.qty().signum() <= 0) {
                    throw new ProblemException("m4.delivery.qty_not_positive", Map.of("orderLineId", orderLineId));
                }
                BigDecimal total =
                        onThisNote.getOrDefault(orderLineId, BigDecimal.ZERO).add(line.qty());
                if (total.compareTo(source.undispatched()) > 0) {
                    throw new ProblemException(
                            "m4.delivery.exceeds_allocation",
                            Map.of("orderLineId", orderLineId, "undispatched", source.undispatched()));
                }
                onThisNote.put(orderLineId, total);
                dropOrders.add(source.orderId());
                UUID lineId = Ids.next();
                lineNo++;
                BigDecimal lineTotal = source.tierPrice() == null
                        ? null
                        : source.tierPrice().multiply(line.qty()).setScale(2, RoundingMode.HALF_UP);
                kernelLines.add(TradingDocuments.line(
                        lineId,
                        noteId,
                        lineNo,
                        source.skuId(),
                        line.batchId(),
                        source.uomCode(),
                        line.qty(),
                        source.tierPrice(),
                        null,
                        null,
                        lineTotal,
                        null,
                        orderLineId));
                lineRows.add(new Object[] {lineId, dropId, source.orderId(), orderLineId, line.qty()});
            }
            allOrders.addAll(dropOrders);
            dropRows.add(new Object[] {dropId, seq, shipTo, billTo, dropOrders.toArray(new UUID[0])});
        }

        documents.save(
                TradingDocuments.draft(noteId, DeliveryReads.DN, seller, buyer, null, scope.userId(), null, null));
        documents.saveLines(noteId, kernelLines);
        jdbc.update(
                """
                insert into trading.doc_delivery (document_id, seller_entity_id, buyer_entity_id, vehicle_ref,
                    driver_name, route_ref)
                values (?, ?, ?, ?, ?, ?)
                """,
                noteId,
                seller,
                buyer,
                command.vehicleRef(),
                command.driverName(),
                command.routeRef());
        for (Object[] drop : dropRows) {
            jdbc.update(
                    """
                    insert into trading.doc_delivery_drop (drop_id, document_id, seq, ship_to_location_id,
                        bill_to_entity_id, order_ids)
                    values (?, ?, ?, ?, ?, ?)
                    """,
                    drop[0],
                    noteId,
                    drop[1],
                    drop[2],
                    drop[3],
                    drop[4]);
        }
        for (Object[] line : lineRows) {
            jdbc.update(
                    """
                    insert into trading.doc_delivery_line (line_id, document_id, drop_id, order_id, order_line_id,
                        dispatched_qty)
                    values (?, ?, ?, ?, ?, ?)
                    """,
                    line[0],
                    noteId,
                    line[1],
                    line[2],
                    line[3],
                    line[4]);
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("deliveryNoteId", noteId);
        after.put("buyerEntityId", buyer);
        after.put("orderIds", List.copyOf(allOrders));
        after.put("drops", dropRows.size());
        after.put("lines", lineRows.size());
        audit.record(AUDIT_CREATED, Subject.of("delivery_note", noteId), null, after, scope);

        events.publish(new DeliveryNoteCreated(noteId, seller, buyer, List.copyOf(allOrders), reads.drops(noteId)));
        return noteId;
    }
}
