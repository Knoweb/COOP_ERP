package lk.coopfed.knoweb.m4trading.internal.queries;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.query.OrderView;
import lk.coopfed.knoweb.m4trading.query.OrderView.OrderLineView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The orders, read through the document base (the header and lines) and M4's extension rows (the
 * request and the seller's allocation). A list reads the ids from {@code trading.doc_order} and
 * each header through {@link DocumentBaseRepository}: M4 reads no kernel table itself. Unpaged for
 * the demo (the cursor of 24A section 5 is deferred).
 */
@Service
class OrderQueriesImpl implements OrderQueries {

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final TradingClock clock;

    OrderQueriesImpl(JdbcTemplate jdbc, DocumentBaseRepository documents, TradingClock clock) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderView> getOrder(UUID orderId, ScopeContext scope) {
        if (orderId == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select o.document_id, o.relationship_id, o.buyer_entity_id, o.seller_entity_id, o.requested_eta,
                       a.status as allocation_status, a.committed_eta, a.lock_at, a.reason_code
                  from trading.doc_order o
                  left join trading.order_allocation a on a.order_id = o.document_id
                 where o.document_id = ?
                """,
                orderId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return documents.findById(orderId).map(header -> view(header, rows.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderView> listOrders(Role role, String status, ScopeContext scope) {
        if (scope == null || scope.entityId() == null) {
            return List.of();
        }
        String column = role == Role.SELLER ? "seller_entity_id" : "buyer_entity_id";
        List<UUID> ids = jdbc.queryForList(
                "select document_id from trading.doc_order where " + column + " = ? order by created_at desc",
                UUID.class,
                scope.entityId());
        List<OrderView> views = new ArrayList<>();
        for (UUID id : ids) {
            getOrder(id, scope)
                    .filter(view -> status == null || status.isBlank() || status.equals(view.status()))
                    // A seller sees an order once it is submitted: a draft is the buyer's alone.
                    .filter(view -> role != Role.SELLER || !OrderStatus.DRAFT.equals(view.status()))
                    .ifPresent(views::add);
        }
        return views;
    }

    private OrderView view(DocumentRecord header, Map<String, Object> row) {
        UUID orderId = header.id();
        Map<UUID, Map<String, Object>> request = byLine(jdbc.queryForList(
                "select line_id, requested_qty, cancelled_qty from trading.doc_order_line where document_id = ?",
                orderId));
        Map<UUID, Map<String, Object>> allocation = byLine(jdbc.queryForList(
                """
                select order_line_id as line_id, allocated_qty, fulfilled_qty, tier_price
                  from trading.order_allocation_line where order_id = ?
                """,
                orderId));

        List<OrderLineView> lines = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        BigDecimal fulfilled = BigDecimal.ZERO;
        for (DocumentLineRecord line : documents.findLines(orderId)) {
            Map<String, Object> req = request.getOrDefault(line.id(), Map.of());
            Map<String, Object> alloc = allocation.get(line.id());
            BigDecimal allocatedQty = alloc == null ? null : (BigDecimal) alloc.get("allocated_qty");
            BigDecimal fulfilledQty = alloc == null ? null : (BigDecimal) alloc.get("fulfilled_qty");
            if (allocatedQty != null) {
                allocated = allocated.add(allocatedQty);
                fulfilled = fulfilled.add(fulfilledQty);
            }
            lines.add(new OrderLineView(
                    line.id(),
                    line.lineNo(),
                    line.skuId(),
                    line.uomCode(),
                    req.get("requested_qty") == null ? line.qty() : (BigDecimal) req.get("requested_qty"),
                    req.get("cancelled_qty") == null ? BigDecimal.ZERO : (BigDecimal) req.get("cancelled_qty"),
                    line.unitPrice(),
                    allocatedQty,
                    fulfilledQty,
                    alloc == null ? null : (BigDecimal) alloc.get("tier_price")));
        }

        Instant lockAt = instant(row.get("lock_at"));
        String status = OrderStatus.derive(
                header.status(), (String) row.get("allocation_status"), lockAt, clock.now(), allocated, fulfilled);

        return new OrderView(
                orderId,
                header.docNumberDisplay(),
                status,
                (UUID) row.get("relationship_id"),
                (UUID) row.get("buyer_entity_id"),
                (UUID) row.get("seller_entity_id"),
                date(row.get("requested_eta")),
                date(row.get("committed_eta")),
                lockAt,
                header.issuedAt(),
                (String) row.get("reason_code"),
                header.isIssued() ? header.netAmount() : draftNet(lines),
                header.notes(),
                lines);
    }

    private static BigDecimal draftNet(List<OrderLineView> lines) {
        BigDecimal net = BigDecimal.ZERO;
        for (OrderLineView line : lines) {
            if (line.indicativePrice() != null) {
                net = net.add(line.indicativePrice().multiply(line.requestedQty()));
            }
        }
        return net.setScale(2, RoundingMode.HALF_UP);
    }

    private static Map<UUID, Map<String, Object>> byLine(List<Map<String, Object>> rows) {
        Map<UUID, Map<String, Object>> map = new HashMap<>();
        rows.forEach(row -> map.put((UUID) row.get("line_id"), row));
        return map;
    }

    private static Instant instant(Object value) {
        return value instanceof Timestamp ts ? ts.toInstant() : null;
    }

    private static LocalDate date(Object value) {
        return value instanceof java.sql.Date d ? d.toLocalDate() : null;
    }
}
