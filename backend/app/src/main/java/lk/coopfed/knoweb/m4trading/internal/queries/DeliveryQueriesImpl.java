package lk.coopfed.knoweb.m4trading.internal.queries;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.api.DropSummary;
import lk.coopfed.knoweb.m4trading.internal.delivery.DeliveryReads;
import lk.coopfed.knoweb.m4trading.query.DeliveryQueries;
import lk.coopfed.knoweb.m4trading.query.DeliveryView;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The delivery notes, from the document base and M4's extension rows; unpaged for the demo. */
@Service
class DeliveryQueriesImpl implements DeliveryQueries {

    static final String PLANNED = "PLANNED";
    static final String RECEIVED = "RECEIVED";
    static final String CLOSED = "CLOSED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final DeliveryReads reads;

    DeliveryQueriesImpl(JdbcTemplate jdbc, DocumentBaseRepository documents, DeliveryReads reads) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.reads = reads;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeliveryView> getDeliveryNote(UUID deliveryNoteId, ScopeContext scope) {
        if (deliveryNoteId == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select seller_entity_id, buyer_entity_id, vehicle_ref, driver_name, dispatched_at
                  from trading.doc_delivery where document_id = ?
                """,
                deliveryNoteId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return documents.findById(deliveryNoteId).map(header -> view(header, rows.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryView> listDeliveryNotes(OrderQueries.Role role, ScopeContext scope) {
        if (scope == null || scope.entityId() == null) {
            return List.of();
        }
        String column = role == OrderQueries.Role.BUYER ? "buyer_entity_id" : "seller_entity_id";
        List<DeliveryView> views = new ArrayList<>();
        for (UUID id : jdbc.queryForList(
                "select document_id from trading.doc_delivery where " + column + " = ? order by created_at desc",
                UUID.class,
                scope.entityId())) {
            getDeliveryNote(id, scope)
                    .filter(view -> role != OrderQueries.Role.BUYER || view.issuedAt() != null)
                    .ifPresent(views::add);
        }
        return views;
    }

    private DeliveryView view(DocumentRecord header, Map<String, Object> row) {
        List<DeliveryView.DropView> drops = new ArrayList<>();
        boolean allReceived = true;
        for (DropSummary drop : reads.drops(header.id())) {
            UUID grnId = receivingGrn(drop.dropId());
            allReceived &= grnId != null;
            drops.add(new DeliveryView.DropView(
                    drop.dropId(),
                    drop.seq(),
                    drop.shipToLocationId(),
                    drop.billToEntityId(),
                    drop.orderIds(),
                    grnId == null ? PLANNED : RECEIVED,
                    grnId,
                    drop.lines()));
        }
        String status = header.isIssued() && !drops.isEmpty() && allReceived ? CLOSED : header.status();
        return new DeliveryView(
                header.id(),
                header.docNumberDisplay(),
                status,
                (UUID) row.get("seller_entity_id"),
                (UUID) row.get("buyer_entity_id"),
                (String) row.get("vehicle_ref"),
                (String) row.get("driver_name"),
                row.get("dispatched_at") instanceof Timestamp ts ? ts.toInstant() : (Instant) null,
                header.issuedAt(),
                drops);
    }

    /** The issued GRN that received this drop, if any (the receiver's document; the seller reads it as counterparty). */
    private UUID receivingGrn(UUID dropId) {
        for (UUID grnId : jdbc.queryForList(
                "select document_id from trading.doc_grn where drop_id = ?", UUID.class, dropId)) {
            if (documents.findById(grnId).map(DocumentRecord::isIssued).orElse(false)) {
                return grnId;
            }
        }
        return null;
    }
}
