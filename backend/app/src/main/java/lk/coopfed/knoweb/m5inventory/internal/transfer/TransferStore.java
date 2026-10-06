package lk.coopfed.knoweb.m5inventory.internal.transfer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The transfer handlers' reads, in their transaction and scope (row-level security applies). */
@Component
class TransferStore {

    record Header(UUID transferId, UUID ownerEntityId, UUID fromLocationId, UUID toLocationId, UUID issuedBy) {}

    record Line(UUID lineId, int lineNo, UUID batchId, UUID skuId, BigDecimal qty, BigDecimal unitCost) {}

    private final JdbcTemplate jdbc;

    TransferStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The transfer as the scope sees it: its source (own_read) or its destination (dest_read).
     * Not locked (the table grants no UPDATE, which FOR SHARE needs): two receipts at once meet
     * at the receipt's primary key, and the second rolls back whole.
     */
    Optional<Header> find(UUID transferId) {
        return jdbc
                .query(
                        """
                        select transfer_id, owner_entity_id, location_id, to_location_id, issued_by
                          from inventory.transfer
                         where transfer_id = ?
                        """,
                        (rs, n) -> new Header(
                                rs.getObject("transfer_id", UUID.class),
                                rs.getObject("owner_entity_id", UUID.class),
                                rs.getObject("location_id", UUID.class),
                                rs.getObject("to_location_id", UUID.class),
                                rs.getObject("issued_by", UUID.class)),
                        transferId)
                .stream()
                .findFirst();
    }

    List<Line> lines(UUID transferId) {
        return jdbc.query(
                """
                select line_id, line_no, batch_id, sku_id, qty, unit_cost
                  from inventory.transfer_line
                 where transfer_id = ?
                 order by line_no
                """,
                (rs, n) -> new Line(
                        rs.getObject("line_id", UUID.class),
                        rs.getInt("line_no"),
                        rs.getObject("batch_id", UUID.class),
                        rs.getObject("sku_id", UUID.class),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("unit_cost")),
                transferId);
    }

    /** The receipt exists: the primary key refuses a second one anyway; this gives the reason. */
    boolean received(UUID transferId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from inventory.transfer_receipt where transfer_id = ?)",
                Boolean.class,
                transferId));
    }

    /** What the open pick lists of delivery notes hold of the GOOD lot of the batch at the location. */
    BigDecimal reserved(UUID locationId, UUID batchId) {
        BigDecimal held = jdbc.queryForObject(
                """
                select coalesce(sum(p.qty), 0)
                  from inventory.pick_list_line p
                  join inventory.pick_list pl on pl.pick_list_id = p.pick_list_id
                  join inventory.stock_lot l on l.stock_lot_id = p.stock_lot_id
                 where pl.status = 'OPEN' and l.location_id = ? and l.batch_id = ? and l.condition = 'GOOD'
                """,
                BigDecimal.class,
                locationId,
                batchId);
        return held == null ? BigDecimal.ZERO : held;
    }
}
