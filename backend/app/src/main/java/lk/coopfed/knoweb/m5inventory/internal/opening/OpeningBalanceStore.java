package lk.coopfed.knoweb.m5inventory.internal.opening;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The opening balance handlers' reads, in their transaction and scope. */
@Component
class OpeningBalanceStore {

    record Header(
            UUID openingBalanceId,
            UUID ownerEntityId,
            UUID locationId,
            String status,
            UUID preparedBy,
            UUID signedEntityBy) {}

    record Line(
            UUID lineId, int lineNo, UUID batchId, UUID skuId, String condition, BigDecimal qty, BigDecimal unitCost) {}

    private final JdbcTemplate jdbc;

    OpeningBalanceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The balance, locked for the rest of the transaction; empty when the scope does not see it. */
    Optional<Header> lock(UUID openingBalanceId) {
        return jdbc
                .query(
                        """
                        select opening_balance_id, owner_entity_id, location_id, status, prepared_by, signed_entity_by
                          from inventory.opening_balance
                         where opening_balance_id = ?
                           for update
                        """,
                        (rs, n) -> new Header(
                                rs.getObject("opening_balance_id", UUID.class),
                                rs.getObject("owner_entity_id", UUID.class),
                                rs.getObject("location_id", UUID.class),
                                rs.getString("status"),
                                rs.getObject("prepared_by", UUID.class),
                                rs.getObject("signed_entity_by", UUID.class)),
                        openingBalanceId)
                .stream()
                .findFirst();
    }

    List<Line> lines(UUID openingBalanceId) {
        return jdbc.query(
                """
                select line_id, line_no, batch_id, sku_id, condition, qty, unit_cost
                  from inventory.opening_balance_line
                 where opening_balance_id = ?
                 order by line_no
                """,
                (rs, n) -> new Line(
                        rs.getObject("line_id", UUID.class),
                        rs.getInt("line_no"),
                        rs.getObject("batch_id", UUID.class),
                        rs.getObject("sku_id", UUID.class),
                        rs.getString("condition"),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("unit_cost")),
                openingBalanceId);
    }

    /** Stock has moved at the location: an opening balance is the first stock, or none (doc 25 flow 6.9). */
    boolean locationHasMovements(UUID locationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from inventory.stock_movement where location_id = ?)",
                Boolean.class,
                locationId));
    }

    boolean openBalanceAt(UUID locationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from inventory.opening_balance where location_id = ? and status <> 'POSTED')",
                Boolean.class,
                locationId));
    }
}
