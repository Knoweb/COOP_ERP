package lk.coopfed.knoweb.m5inventory.internal.consumers;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The reads of the consumer-applied commands, in the handler's transaction and scope. */
@Component
class ConsumerStore {

    /** A GOOD lot with stock that a delivery line may be picked from, with what is free of it. */
    record Candidate(UUID stockLotId, UUID locationId, UUID batchId, BigDecimal free) {}

    /** One pick of a pick list: a lot and a quantity, or the short part of a line (no lot). */
    record Pick(UUID locationId, UUID stockLotId, UUID batchId, BigDecimal qty) {}

    record PickList(UUID pickListId, String status) {}

    /** A short row of a pick list: the delivery line, its item, the batch it named (or null), the quantity not found. */
    record ShortPick(UUID deliveryLineId, UUID skuId, UUID batchId, BigDecimal qty) {}

    private final JdbcTemplate jdbc;

    ConsumerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    int movementsCiting(UUID documentId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from inventory.stock_movement where document_id = ?", Integer.class, documentId);
        return count == null ? 0 : count;
    }

    /**
     * The GOOD lots of the SKU with stock, of every location the scope reads, in FEFO order
     * (expiry, none last, then received), each with its quantity less what open pick lists hold of
     * it; locked for the rest of the transaction so two delivery notes cannot reserve the same
     * units. Only the named batch when the delivery line names one. Only lots that expire on or
     * after {@code expiresFrom} (wave 2, M5-01): the business date plus the seller's minimum shelf
     * life for a delivery note, so an expired lot never leaves on one.
     */
    List<Candidate> lockCandidates(UUID skuId, UUID batchId, LocalDate expiresFrom) {
        return jdbc.query(
                """
                select l.stock_lot_id, l.location_id, l.batch_id,
                       l.qty_on_hand - coalesce((select sum(p.qty)
                                                   from inventory.pick_list_line p
                                                   join inventory.pick_list pl on pl.pick_list_id = p.pick_list_id
                                                  where pl.status = 'OPEN' and p.stock_lot_id = l.stock_lot_id), 0) as free
                  from inventory.stock_lot l
                 where l.sku_id = ? and l.condition = 'GOOD' and l.qty_on_hand > 0
                   and (?::uuid is null or l.batch_id = ?)
                   and (l.expiry_date is null or l.expiry_date >= ?)
                 order by l.expiry_date nulls last, l.received_at, l.stock_lot_id
                   for update of l
                """,
                (rs, n) -> new Candidate(
                        rs.getObject("stock_lot_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getObject("batch_id", UUID.class),
                        rs.getBigDecimal("free")),
                skuId,
                batchId,
                batchId,
                Date.valueOf(expiresFrom));
    }

    Optional<PickList> pickListOf(UUID deliveryDocumentId) {
        return jdbc
                .query(
                        "select pick_list_id, status from inventory.pick_list where delivery_document_id = ? for update",
                        (rs, n) -> new PickList(rs.getObject("pick_list_id", UUID.class), rs.getString("status")),
                        deliveryDocumentId)
                .stream()
                .findFirst();
    }

    /** The short parts of a list: a row per delivery line with no lot, what was not found at the reservation. */
    List<ShortPick> shortPicks(UUID pickListId) {
        return jdbc.query(
                """
                select delivery_line_id, sku_id, batch_id, qty
                  from inventory.pick_list_line
                 where pick_list_id = ? and stock_lot_id is null
                 order by delivery_line_id
                """,
                (rs, n) -> new ShortPick(
                        rs.getObject("delivery_line_id", UUID.class),
                        rs.getObject("sku_id", UUID.class),
                        rs.getObject("batch_id", UUID.class),
                        rs.getBigDecimal("qty")),
                pickListId);
    }

    /** The picks of a list that name a lot (the short parts move nothing). */
    List<Pick> lotPicks(UUID pickListId) {
        return jdbc.query(
                """
                select location_id, stock_lot_id, batch_id, qty
                  from inventory.pick_list_line
                 where pick_list_id = ? and stock_lot_id is not null
                 order by location_id, batch_id
                """,
                (rs, n) -> new Pick(
                        rs.getObject("location_id", UUID.class),
                        rs.getObject("stock_lot_id", UUID.class),
                        rs.getObject("batch_id", UUID.class),
                        rs.getBigDecimal("qty")),
                pickListId);
    }
}
