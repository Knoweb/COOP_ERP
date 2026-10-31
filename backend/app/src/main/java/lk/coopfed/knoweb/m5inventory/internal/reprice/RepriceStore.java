package lk.coopfed.knoweb.m5inventory.internal.reprice;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.internal.ledger.CostService.CostRow;
import lk.coopfed.knoweb.m5inventory.query.StockRepricingView;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database queries for stock lot repricing and historical revaluation records (PGL-M2-03).
 * In accordance with ArchUnit rule {@code onlyCommandHandlersWriteToTheDatabase}, only
 * reads are performed here; mutations are performed directly by the {@code @CommandHandler}.
 */
@Repository
public class RepriceStore {

    public record LotRecord(
            UUID stockLotId,
            UUID ownerEntityId,
            UUID locationId,
            UUID batchId,
            UUID skuId,
            Date expiryDate,
            LotCondition condition,
            BigDecimal qtyOnHand,
            BigDecimal unitCost,
            Instant receivedAt) {}

    private final JdbcTemplate jdbc;

    public RepriceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LotRecord> findLot(UUID stockLotId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    select stock_lot_id, owner_entity_id, location_id, batch_id, sku_id,
                           expiry_date, condition, qty_on_hand, unit_cost, received_at
                      from inventory.stock_lot
                     where stock_lot_id = ?
                    """,
                    (rs, rowNum) -> mapLot(rs),
                    stockLotId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<LotRecord> findLotByCondition(UUID locationId, UUID batchId, LotCondition condition) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    select stock_lot_id, owner_entity_id, location_id, batch_id, sku_id,
                           expiry_date, condition, qty_on_hand, unit_cost, received_at
                      from inventory.stock_lot
                     where location_id = ? and batch_id = ? and condition = ?
                    """,
                    (rs, rowNum) -> mapLot(rs),
                    locationId,
                    batchId,
                    condition.name()));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<CostRow> lockEntityCost(UUID ownerEntityId, UUID skuId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    select qty_on_hand, avg_cost
                      from inventory.entity_sku_cost
                     where owner_entity_id = ? and sku_id = ?
                     for update
                    """,
                    (rs, rowNum) -> new CostRow(rs.getBigDecimal("qty_on_hand"), rs.getBigDecimal("avg_cost")),
                    ownerEntityId,
                    skuId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public List<StockRepricingView> findRepricings(UUID locationId, UUID skuId, ScopeContext scope) {
        if (skuId != null) {
            return jdbc.query(
                    """
                    select repricing_id, owner_entity_id, location_id, stock_lot_id, sku_id, batch_id,
                           condition, qty_affected, previous_unit_cost, new_unit_cost, target_condition,
                           target_lot_id, value_adjustment, reason, repriced_by, repriced_at
                      from inventory.stock_repricing
                     where location_id = ? and sku_id = ?
                     order by repriced_at desc
                    """,
                    (rs, rowNum) -> mapRepricing(rs),
                    locationId,
                    skuId);
        }
        return jdbc.query(
                """
                select repricing_id, owner_entity_id, location_id, stock_lot_id, sku_id, batch_id,
                       condition, qty_affected, previous_unit_cost, new_unit_cost, target_condition,
                       target_lot_id, value_adjustment, reason, repriced_by, repriced_at
                  from inventory.stock_repricing
                 where location_id = ?
                 order by repriced_at desc
                """,
                (rs, rowNum) -> mapRepricing(rs),
                locationId);
    }

    private static LotRecord mapLot(ResultSet rs) throws SQLException {
        return new LotRecord(
                rs.getObject("stock_lot_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getObject("batch_id", UUID.class),
                rs.getObject("sku_id", UUID.class),
                rs.getDate("expiry_date"),
                LotCondition.valueOf(rs.getString("condition")),
                rs.getBigDecimal("qty_on_hand"),
                rs.getBigDecimal("unit_cost"),
                rs.getTimestamp("received_at").toInstant());
    }

    private static StockRepricingView mapRepricing(ResultSet rs) throws SQLException {
        return new StockRepricingView(
                rs.getObject("repricing_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getObject("stock_lot_id", UUID.class),
                rs.getObject("sku_id", UUID.class),
                rs.getObject("batch_id", UUID.class),
                LotCondition.valueOf(rs.getString("condition")),
                rs.getBigDecimal("qty_affected"),
                rs.getBigDecimal("previous_unit_cost"),
                rs.getBigDecimal("new_unit_cost"),
                LotCondition.valueOf(rs.getString("target_condition")),
                rs.getObject("target_lot_id", UUID.class),
                rs.getBigDecimal("value_adjustment"),
                rs.getString("reason"),
                rs.getObject("repriced_by", UUID.class),
                rs.getTimestamp("repriced_at").toInstant());
    }
}
