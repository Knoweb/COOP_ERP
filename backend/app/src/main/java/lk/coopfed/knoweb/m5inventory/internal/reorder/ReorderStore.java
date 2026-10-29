package lk.coopfed.knoweb.m5inventory.internal.reorder;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m5inventory.internal.control.BusinessDay;
import lk.coopfed.knoweb.m5inventory.query.ReorderControlView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Database operations for reorder controls and stock balance queries (PGL-M2-01).
 *
 * <p>In accordance with repository invariants (AGENTS.md), reorder controls are never
 * physically deleted; active queries filter on {@code status = 'ACTIVE'}.
 */
@Repository
public class ReorderStore {

    private final JdbcTemplate jdbc;
    private final BusinessDay businessDay;

    public ReorderStore(JdbcTemplate jdbc, BusinessDay businessDay) {
        this.jdbc = jdbc;
        this.businessDay = businessDay;
    }

    public List<ReorderControlView> findControls(UUID locationId, UUID skuId, ScopeContext scope) {
        Date today = Date.valueOf(businessDay.today());
        return jdbc.query(
                """
                select rc.reorder_control_id, rc.owner_entity_id, rc.location_id, rc.sku_id,
                       rc.reorder_level, rc.reorder_qty, rc.max_qty,
                       rc.alert_triggered, rc.last_alerted_at, rc.last_alert_qty,
                       coalesce(stock.on_hand, 0) as current_on_hand,
                       coalesce(stock.available, 0) as current_available
                  from inventory.reorder_control rc
                  left join lateral (
                       select coalesce(sum(l.qty_on_hand), 0) as on_hand,
                              greatest(0, coalesce(sum(case when (l.expiry_date is null or l.expiry_date >= ?) then l.qty_on_hand else 0 end), 0)
                              - coalesce((select sum(p.qty)
                                            from inventory.pick_list_line p
                                            join inventory.pick_list pl on pl.pick_list_id = p.pick_list_id
                                            left join inventory.pick_override o on o.pick_line_id = p.pick_line_id
                                            join inventory.stock_lot r on r.stock_lot_id = coalesce(o.replacement_stock_lot_id, p.stock_lot_id)
                                           where pl.status = 'OPEN'
                                             and coalesce(o.location_id, p.location_id) = rc.location_id
                                             and p.sku_id = rc.sku_id
                                             and (r.expiry_date is null or r.expiry_date >= ?)), 0)) as available
                         from inventory.stock_lot l
                        where l.location_id = rc.location_id
                          and l.sku_id = rc.sku_id
                          and l.condition = 'GOOD'
                          and l.qty_on_hand > 0
                  ) stock on true
                 where rc.location_id = ?
                   and (?::uuid is null or rc.sku_id = ?::uuid)
                   and rc.status = 'ACTIVE'
                 order by rc.created_at desc
                """,
                (rs, rowNum) -> new ReorderControlView(
                        rs.getObject("reorder_control_id", UUID.class),
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getObject("sku_id", UUID.class),
                        rs.getBigDecimal("reorder_level"),
                        rs.getBigDecimal("reorder_qty"),
                        rs.getBigDecimal("max_qty"),
                        rs.getBoolean("alert_triggered"),
                        toInstant(rs.getTimestamp("last_alerted_at")),
                        rs.getBigDecimal("last_alert_qty"),
                        rs.getBigDecimal("current_on_hand"),
                        rs.getBigDecimal("current_available")),
                today,
                today,
                locationId,
                skuId,
                skuId);
    }

    public StockQuantities calculateStock(UUID locationId, UUID skuId) {
        Date today = Date.valueOf(businessDay.today());
        List<StockQuantities> results = jdbc.query(
                """
                select coalesce(sum(l.qty_on_hand), 0) as on_hand,
                       greatest(0, coalesce(sum(case when (l.expiry_date is null or l.expiry_date >= ?) then l.qty_on_hand else 0 end), 0)
                       - coalesce((select sum(p.qty)
                                     from inventory.pick_list_line p
                                     join inventory.pick_list pl on pl.pick_list_id = p.pick_list_id
                                     left join inventory.pick_override o on o.pick_line_id = p.pick_line_id
                                     join inventory.stock_lot r on r.stock_lot_id = coalesce(o.replacement_stock_lot_id, p.stock_lot_id)
                                    where pl.status = 'OPEN'
                                      and coalesce(o.location_id, p.location_id) = ?
                                      and p.sku_id = ?
                                      and (r.expiry_date is null or r.expiry_date >= ?)), 0)) as available
                  from inventory.stock_lot l
                 where l.location_id = ?
                   and l.sku_id = ?
                   and l.condition = 'GOOD'
                   and l.qty_on_hand > 0
                """,
                (rs, rowNum) -> new StockQuantities(rs.getBigDecimal("on_hand"), rs.getBigDecimal("available")),
                today,
                locationId,
                skuId,
                today,
                locationId,
                skuId);
        return results.isEmpty() ? new StockQuantities(BigDecimal.ZERO, BigDecimal.ZERO) : results.getFirst();
    }

    public Optional<ReorderControlRecord> findControlForUpdate(UUID ownerEntityId, UUID locationId, UUID skuId) {
        List<ReorderControlRecord> list = jdbc.query(
                """
                select reorder_control_id, owner_entity_id, location_id, sku_id,
                       reorder_level, reorder_qty, max_qty, alert_triggered, last_alerted_at, last_alert_qty, status
                  from inventory.reorder_control
                 where owner_entity_id = ? and location_id = ? and sku_id = ?
                   for update
                """,
                (rs, rowNum) -> new ReorderControlRecord(
                        rs.getObject("reorder_control_id", UUID.class),
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getObject("sku_id", UUID.class),
                        rs.getBigDecimal("reorder_level"),
                        rs.getBigDecimal("reorder_qty"),
                        rs.getBigDecimal("max_qty"),
                        rs.getBoolean("alert_triggered"),
                        toInstant(rs.getTimestamp("last_alerted_at")),
                        rs.getBigDecimal("last_alert_qty"),
                        rs.getString("status")),
                ownerEntityId,
                locationId,
                skuId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    public List<ReorderControlRecord> findControlsAtLocationForUpdate(UUID ownerEntityId, UUID locationId) {
        return jdbc.query(
                """
                select reorder_control_id, owner_entity_id, location_id, sku_id,
                       reorder_level, reorder_qty, max_qty, alert_triggered, last_alerted_at, last_alert_qty, status
                  from inventory.reorder_control
                 where owner_entity_id = ? and location_id = ? and status = 'ACTIVE'
                   for update
                """,
                (rs, rowNum) -> new ReorderControlRecord(
                        rs.getObject("reorder_control_id", UUID.class),
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getObject("sku_id", UUID.class),
                        rs.getBigDecimal("reorder_level"),
                        rs.getBigDecimal("reorder_qty"),
                        rs.getBigDecimal("max_qty"),
                        rs.getBoolean("alert_triggered"),
                        toInstant(rs.getTimestamp("last_alerted_at")),
                        rs.getBigDecimal("last_alert_qty"),
                        rs.getString("status")),
                ownerEntityId,
                locationId);
    }

    public List<LocationEntityKey> findDistinctLocationsWithControls(ScopeContext view) {
        return jdbc.query(
                """
                select distinct owner_entity_id, location_id
                  from inventory.reorder_control
                 where status = 'ACTIVE'
                 order by owner_entity_id, location_id
                """,
                (rs, rowNum) -> new LocationEntityKey(
                        rs.getObject("owner_entity_id", UUID.class), rs.getObject("location_id", UUID.class)));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public record StockQuantities(BigDecimal onHand, BigDecimal available) {}

    public record LocationEntityKey(UUID ownerEntityId, UUID locationId) {}

    public record ReorderControlRecord(
            UUID reorderControlId,
            UUID ownerEntityId,
            UUID locationId,
            UUID skuId,
            BigDecimal reorderLevel,
            BigDecimal reorderQty,
            BigDecimal maxQty,
            boolean alertTriggered,
            Instant lastAlertedAt,
            BigDecimal lastAlertQty,
            String status) {
        public boolean isActive() {
            return "ACTIVE".equals(status);
        }
    }
}
