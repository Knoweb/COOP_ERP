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

    record Header(UUID transferId, UUID ownerEntityId, UUID fromLocationId, UUID toLocationId) {}

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
                        select transfer_id, owner_entity_id, location_id, to_location_id
                          from inventory.transfer
                         where transfer_id = ?
                        """,
                        (rs, n) -> new Header(
                                rs.getObject("transfer_id", UUID.class),
                                rs.getObject("owner_entity_id", UUID.class),
                                rs.getObject("location_id", UUID.class),
                                rs.getObject("to_location_id", UUID.class)),
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

    /** What the GOOD lot of the batch holds at the location; zero when there is none. */
    BigDecimal goodOnHand(UUID locationId, UUID batchId) {
        List<BigDecimal> found = jdbc.queryForList(
                "select qty_on_hand from inventory.stock_lot where location_id = ? and batch_id = ? and condition = 'GOOD'",
                BigDecimal.class,
                locationId,
                batchId);
        return found.isEmpty() ? BigDecimal.ZERO : found.get(0);
    }
}
