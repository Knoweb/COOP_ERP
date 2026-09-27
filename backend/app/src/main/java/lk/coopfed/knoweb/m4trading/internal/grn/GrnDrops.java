package lk.coopfed.knoweb.m4trading.internal.grn;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m4trading.api.DeliveryLineSummary;
import lk.coopfed.knoweb.m4trading.api.DropSummary;
import lk.coopfed.knoweb.m4trading.internal.delivery.DeliveryReads;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A delivery note drop as its receiver counts it: the seller, the relationship, and per item the
 * expected quantity (the drop's lines of that item together) at the delivery's trade price. The
 * receiver reads the seller's note as its counterparty (document_read). Read-only.
 */
@Component
public class GrnDrops {

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final DeliveryReads deliveries;

    GrnDrops(JdbcTemplate jdbc, DocumentBaseRepository documents, DeliveryReads deliveries) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.deliveries = deliveries;
    }

    /** @throws ProblemException {@code m4.grn.drop_unknown} for a drop not visible or of a note not issued */
    public DropToReceive drop(UUID dropId) {
        List<UUID> notes = jdbc.queryForList(
                "select document_id from trading.doc_delivery_drop where drop_id = ?", UUID.class, dropId);
        if (notes.isEmpty()) {
            throw new ProblemException("m4.grn.drop_unknown");
        }
        UUID noteId = notes.get(0);
        DocumentRecord note = documents
                .findById(noteId)
                .filter(DocumentRecord::isIssued)
                .orElseThrow(() -> new ProblemException("m4.grn.drop_unknown"));
        DropSummary drop = deliveries.drops(noteId).stream()
                .filter(candidate -> candidate.dropId().equals(dropId))
                .findFirst()
                .orElseThrow(() -> new ProblemException("m4.grn.drop_unknown"));
        Map<UUID, BigDecimal> prices = new LinkedHashMap<>();
        for (DocumentLineRecord line : documents.findLines(noteId)) {
            prices.put(line.id(), line.unitPrice());
        }
        Map<UUID, ExpectedItem> items = new LinkedHashMap<>();
        for (DeliveryLineSummary line : drop.lines()) {
            ExpectedItem known = items.get(line.skuId());
            items.put(
                    line.skuId(),
                    known == null
                            ? new ExpectedItem(
                                    line.skuId(),
                                    line.uomCode(),
                                    line.dispatchedQty(),
                                    prices.get(line.lineId()),
                                    line.lineId())
                            : new ExpectedItem(
                                    known.skuId(),
                                    known.uomCode(),
                                    known.expectedQty().add(line.dispatchedQty()),
                                    known.unitPrice(),
                                    known.deliveryLineId()));
        }
        // The relationship from the seller's decision on the order, which the receiver reads as
        // its counterparty from any of its sessions; its own order row (location-less) is hidden
        // from a session scoped to the receiving warehouse or shop (M4-11, the demo's stores).
        UUID relationshipId = drop.orderIds().isEmpty()
                ? null
                : jdbc
                        .queryForList(
                                """
                                select relationship_id from trading.order_allocation where order_id = ?
                                union all
                                select relationship_id from trading.doc_order where document_id = ?
                                """,
                                UUID.class,
                                drop.orderIds().get(0),
                                drop.orderIds().get(0))
                        .stream()
                        .findFirst()
                        .orElse(null);
        return new DropToReceive(
                dropId,
                noteId,
                note.ownerEntityId(),
                relationshipId,
                drop.billToEntityId(),
                drop.shipToLocationId(),
                items);
    }

    /** Whether the drop already has a GRN, draft or issued: one GRN per drop. */
    public boolean alreadyCaptured(UUID dropId) {
        Integer count =
                jdbc.queryForObject("select count(*) from trading.doc_grn where drop_id = ?", Integer.class, dropId);
        return count != null && count > 0;
    }

    public record DropToReceive(
            UUID dropId,
            UUID deliveryNoteId,
            UUID sellerEntityId,
            UUID relationshipId,
            UUID billToEntityId,
            UUID shipToLocationId,
            Map<UUID, ExpectedItem> items) {}

    /** @param deliveryLineId the first delivery line of the item, the GRN line's reference line */
    public record ExpectedItem(
            UUID skuId, String uomCode, BigDecimal expectedQty, BigDecimal unitPrice, UUID deliveryLineId) {}
}
