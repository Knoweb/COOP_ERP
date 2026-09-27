package lk.coopfed.knoweb.m5inventory.internal.ledger;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.m5inventory.internal.ledger.CostService.CostRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The ledger's reads, in the caller's transaction and under its row-level security. The writes
 * are {@link LedgerService}'s own: only a command handler writes (ArchitectureTests).
 */
@Component
class LedgerStore {

    /** A lot as the ledger holds it while it posts. */
    record LotRow(
            UUID stockLotId,
            UUID ownerEntityId,
            UUID locationId,
            UUID batchId,
            UUID skuId,
            String condition,
            BigDecimal qtyOnHand,
            BigDecimal unitCost,
            Instant negativeSince,
            long lastMovementSeq) {}

    private final JdbcTemplate jdbc;

    LedgerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The lot, locked for the rest of the transaction (SELECT ... FOR UPDATE). */
    Optional<LotRow> lockLot(UUID locationId, UUID batchId, String condition) {
        return jdbc
                .query(
                        """
                        select stock_lot_id, owner_entity_id, location_id, batch_id, sku_id, condition, qty_on_hand,
                               unit_cost, negative_since, last_movement_seq
                          from inventory.stock_lot
                         where location_id = ? and batch_id = ? and condition = ?
                           for update
                        """,
                        LedgerStore::lot,
                        locationId,
                        batchId,
                        condition)
                .stream()
                .findFirst();
    }

    /** The entity's average for the SKU, locked for the rest of the transaction. */
    Optional<CostRow> lockCost(UUID entityId, UUID skuId) {
        return jdbc
                .query(
                        "select qty_on_hand, avg_cost from inventory.entity_sku_cost"
                                + " where owner_entity_id = ? and sku_id = ? for update",
                        (rs, n) -> new CostRow(rs.getBigDecimal("qty_on_hand"), rs.getBigDecimal("avg_cost")),
                        entityId,
                        skuId)
                .stream()
                .findFirst();
    }

    /** The entity's average for the SKU, unlocked: the cost a new lot starts with. */
    Optional<BigDecimal> averageCost(UUID entityId, UUID skuId) {
        return jdbc
                .queryForList(
                        "select avg_cost from inventory.entity_sku_cost where owner_entity_id = ? and sku_id = ?",
                        BigDecimal.class,
                        entityId,
                        skuId)
                .stream()
                .findFirst();
    }

    private static LotRow lot(ResultSet rs, int n) throws SQLException {
        OffsetDateTime negative = rs.getObject("negative_since", OffsetDateTime.class);
        return new LotRow(
                rs.getObject("stock_lot_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getObject("batch_id", UUID.class),
                rs.getObject("sku_id", UUID.class),
                rs.getString("condition"),
                rs.getBigDecimal("qty_on_hand"),
                rs.getBigDecimal("unit_cost"),
                negative == null ? null : negative.toInstant(),
                rs.getLong("last_movement_seq"));
    }
}
