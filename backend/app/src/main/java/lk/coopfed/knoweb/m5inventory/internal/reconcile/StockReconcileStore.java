package lk.coopfed.knoweb.m5inventory.internal.reconcile;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only store for stock balance reconciliation (ArchitectureTests: only command handlers write).
 *
 * <p>Every query method is {@code @Transactional(readOnly = true)} so that the kernel's
 * {@code ScopeConnectionCustomizer} aspect fires and applies the RLS session variables ({@code
 * kernel.scope_class()}, {@code kernel.scope_entity()}, etc.) before any query runs. Without a
 * transaction the scope variables are never set and RLS policies deny the read.
 */
@Component
@Transactional(readOnly = true)
public class StockReconcileStore {

    public record DriftingLot(
            UUID stockLotId,
            UUID ownerEntityId,
            UUID locationId,
            UUID batchId,
            UUID skuId,
            String condition,
            BigDecimal qtyOnHand,
            BigDecimal movementSum,
            BigDecimal drift) {}

    private final JdbcTemplate jdbc;

    public StockReconcileStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Find all stock lots where materialized {@code qty_on_hand} has drifted from the sum of its
     * ledger movements (doc 18 invariant B-I4; doc 25 section 3.2; 25A section 7.2).
     *
     * <p>Joins on the full canonical identity and tenant keys: {@code (owner_entity_id, location_id,
     * batch_id, sku_id, condition)} to prevent any cross-tenant, cross-location, or cross-item mix.
     *
     * @param scope caller's scope (typically {@code federationView()} for nightly fleet scan)
     * @return list of lots with non-zero drift
     */
    public List<DriftingLot> findDriftingLots(ScopeContext scope) {
        return jdbc.query(
                """
                select
                    l.stock_lot_id,
                    l.owner_entity_id,
                    l.location_id,
                    l.batch_id,
                    l.sku_id,
                    l.condition,
                    l.qty_on_hand,
                    coalesce(sum(m.qty_delta), 0) as movement_sum
                from inventory.stock_lot l
                left join inventory.stock_movement m
                    on m.owner_entity_id = l.owner_entity_id
                   and m.location_id = l.location_id
                   and m.batch_id = l.batch_id
                   and m.sku_id = l.sku_id
                   and m.condition = l.condition
                group by
                    l.stock_lot_id,
                    l.owner_entity_id,
                    l.location_id,
                    l.batch_id,
                    l.sku_id,
                    l.condition,
                    l.qty_on_hand
                having l.qty_on_hand <> coalesce(sum(m.qty_delta), 0)
                order by l.owner_entity_id, l.location_id, l.stock_lot_id
                """,
                (rs, rowNum) -> {
                    BigDecimal qtyOnHand = rs.getBigDecimal("qty_on_hand");
                    BigDecimal movementSum = rs.getBigDecimal("movement_sum");
                    BigDecimal drift = qtyOnHand.subtract(movementSum);
                    return new DriftingLot(
                            rs.getObject("stock_lot_id", UUID.class),
                            rs.getObject("owner_entity_id", UUID.class),
                            rs.getObject("location_id", UUID.class),
                            rs.getObject("batch_id", UUID.class),
                            rs.getObject("sku_id", UUID.class),
                            rs.getString("condition"),
                            qtyOnHand,
                            movementSum,
                            drift);
                });
    }

    /**
     * Find stock lots with drift at a specific location.
     *
     * @param locationId location to check
     * @param scope caller's scope (OWN scope of the location's entity)
     * @return list of lots at the location with non-zero drift
     */
    public List<DriftingLot> findDriftingLots(UUID locationId, ScopeContext scope) {
        return jdbc.query(
                """
                select
                    l.stock_lot_id,
                    l.owner_entity_id,
                    l.location_id,
                    l.batch_id,
                    l.sku_id,
                    l.condition,
                    l.qty_on_hand,
                    coalesce(sum(m.qty_delta), 0) as movement_sum
                from inventory.stock_lot l
                left join inventory.stock_movement m
                    on m.owner_entity_id = l.owner_entity_id
                   and m.location_id = l.location_id
                   and m.batch_id = l.batch_id
                   and m.sku_id = l.sku_id
                   and m.condition = l.condition
                where l.location_id = ?
                group by
                    l.stock_lot_id,
                    l.owner_entity_id,
                    l.location_id,
                    l.batch_id,
                    l.sku_id,
                    l.condition,
                    l.qty_on_hand
                having l.qty_on_hand <> coalesce(sum(m.qty_delta), 0)
                order by l.stock_lot_id
                """,
                (rs, rowNum) -> {
                    BigDecimal qtyOnHand = rs.getBigDecimal("qty_on_hand");
                    BigDecimal movementSum = rs.getBigDecimal("movement_sum");
                    BigDecimal drift = qtyOnHand.subtract(movementSum);
                    return new DriftingLot(
                            rs.getObject("stock_lot_id", UUID.class),
                            rs.getObject("owner_entity_id", UUID.class),
                            rs.getObject("location_id", UUID.class),
                            rs.getObject("batch_id", UUID.class),
                            rs.getObject("sku_id", UUID.class),
                            rs.getString("condition"),
                            qtyOnHand,
                            movementSum,
                            drift);
                },
                locationId);
    }
}
