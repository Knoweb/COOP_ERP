package lk.coopfed.knoweb.m4trading.internal.delivery;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m4trading.internal.queries.OrderStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * An order line as the seller delivers it: the buyer's request (item, unit) and the seller's
 * allocation (allocated, fulfilled, tier price). Read-only; the handlers write.
 */
@Component
public class AllocatedLines {

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;

    AllocatedLines(JdbcTemplate jdbc, DocumentBaseRepository documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    /**
     * The line, on an order the caller accepted and the buyer has not cancelled.
     *
     * @param forUpdate lock the seller's allocation line for the transaction (issuance)
     * @throws ProblemException {@code m4.delivery.order_line_unknown} for a line the caller cannot
     *     see; {@code m4.delivery.order_not_accepted} for an order not accepted by the caller
     */
    public AllocatedLine find(UUID orderLineId, UUID sellerEntityId, boolean forUpdate) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select l.document_id as order_id, o.buyer_entity_id, o.seller_entity_id, o.relationship_id,
                       a.status as allocation_status
                  from trading.doc_order_line l
                  join trading.doc_order o on o.document_id = l.document_id
                  left join trading.order_allocation a on a.order_id = l.document_id
                 where l.line_id = ?
                """,
                orderLineId);
        if (rows.isEmpty()) {
            throw new ProblemException("m4.delivery.order_line_unknown", Map.of("orderLineId", orderLineId));
        }
        Map<String, Object> row = rows.get(0);
        UUID orderId = (UUID) row.get("order_id");
        DocumentRecord order = documents.findById(orderId)
                .orElseThrow(() -> new ProblemException("m4.delivery.order_line_unknown", Map.of("orderLineId", orderLineId)));
        if (!sellerEntityId.equals(row.get("seller_entity_id"))
                || !OrderStatus.ACCEPTED.equals(row.get("allocation_status"))
                || !OrderStatus.SUBMITTED.equals(order.status())) {
            throw new ProblemException("m4.delivery.order_not_accepted", Map.of("orderId", orderId));
        }
        Map<String, Object> allocation = jdbc.queryForMap(
                "select allocated_qty, fulfilled_qty, tier_price from trading.order_allocation_line where order_line_id = ?"
                        + (forUpdate ? " for update" : ""),
                orderLineId);
        DocumentLineRecord line = documents.findLines(orderId).stream()
                .filter(candidate -> candidate.id().equals(orderLineId))
                .findFirst()
                .orElseThrow();
        return new AllocatedLine(
                orderLineId,
                orderId,
                (UUID) row.get("buyer_entity_id"),
                (UUID) row.get("relationship_id"),
                line.skuId(),
                line.uomCode(),
                line.qty(),
                (BigDecimal) allocation.get("allocated_qty"),
                (BigDecimal) allocation.get("fulfilled_qty"),
                (BigDecimal) allocation.get("tier_price"));
    }

    public record AllocatedLine(
            UUID orderLineId,
            UUID orderId,
            UUID buyerEntityId,
            UUID relationshipId,
            UUID skuId,
            String uomCode,
            BigDecimal orderedQty,
            BigDecimal allocatedQty,
            BigDecimal fulfilledQty,
            BigDecimal tierPrice) {

        public BigDecimal undispatched() {
            return allocatedQty.subtract(fulfilledQty);
        }
    }
}
