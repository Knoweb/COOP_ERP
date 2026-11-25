package lk.coopfed.knoweb.m5inventory.internal.snapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.SnapshotContributor;
import lk.coopfed.knoweb.m5inventory.internal.control.BusinessDay;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Snapshot contributor for inventory stock lots at a location (doc 25A section 7.3, doc 32 section 5.1).
 *
 * <p>Serves the {@code lot} snapshot table:
 * <ul>
 *   <li>Only lots belonging to the requesting shop location with non-zero quantities.
 *   <li>GOOD, available, in-date lots receive FEFO rank (ordering: expiry date nulls last,
 *       receipt timestamp, stock_lot_id).
 *   <li>DAMAGED, negative, and expired lots receive {@code null} FEFO rank.
 *   <li>Negative lots carry {@code negative = true}.
 *   <li>Zero-quantity lots are excluded (triggering tombstones when depleted).
 *   <li>Unit cost and margins are strictly excluded (data minimization / security rule).
 * </ul>
 */
@Component
public class LotSnapshotContributor implements SnapshotContributor {

    public static final String TABLE = "lot";

    private final JdbcTemplate jdbc;
    private final BusinessDay businessDay;

    LotSnapshotContributor(JdbcTemplate jdbc, BusinessDay businessDay) {
        this.jdbc = jdbc;
        this.businessDay = businessDay;
    }

    @Override
    public Set<String> tables() {
        return Set.of(TABLE);
    }

    @Override
    public Map<UUID, Map<String, Object>> rows(String table, Shop shop, Collection<UUID> rowIds) {
        if (!TABLE.equals(table) || shop == null || rowIds == null || rowIds.isEmpty()) {
            return Map.of();
        }
        return read(shop, rowIds);
    }

    @Override
    public Map<UUID, Map<String, Object>> allRows(String table, Shop shop) {
        if (!TABLE.equals(table) || shop == null) {
            return Map.of();
        }
        return read(shop, null);
    }

    private Map<UUID, Map<String, Object>> read(Shop shop, Collection<UUID> rowIds) {
        Date today = Date.valueOf(businessDay.today());
        String sql =
                """
                select * from (
                    select l.stock_lot_id, l.batch_id, l.sku_id, l.condition, l.qty_on_hand,
                           case when l.condition = 'GOOD' and l.qty_on_hand > 0
                                     and (l.expiry_date is null or l.expiry_date >= d.today)
                                then row_number() over (
                                         partition by l.location_id, l.sku_id,
                                                      (l.condition = 'GOOD' and l.qty_on_hand > 0
                                                       and (l.expiry_date is null or l.expiry_date >= d.today))
                                         order by l.expiry_date nulls last, l.received_at, l.stock_lot_id)
                           end as fefo_rank
                      from inventory.stock_lot l
                     cross join (select ?::date as today) d
                     where l.location_id = ?
                       and l.qty_on_hand <> 0
                """
                        + (rowIds == null ? "" : " and l.stock_lot_id = any(?)")
                        + ") r order by r.sku_id, r.fefo_rank is null, r.fefo_rank, r.condition, r.stock_lot_id";

        return jdbc.query(
                connection -> {
                    PreparedStatement ps = connection.prepareStatement(sql);
                    ps.setDate(1, today);
                    ps.setObject(2, shop.locationId());
                    if (rowIds != null) {
                        ps.setArray(3, uuids(connection, rowIds));
                    }
                    return ps;
                },
                rs -> {
                    Map<UUID, Map<String, Object>> lots = new LinkedHashMap<>();
                    while (rs.next()) {
                        UUID lotId = rs.getObject("stock_lot_id", UUID.class);
                        UUID batchId = rs.getObject("batch_id", UUID.class);
                        UUID skuId = rs.getObject("sku_id", UUID.class);
                        String condition = rs.getString("condition");
                        BigDecimal qty = rs.getBigDecimal("qty_on_hand");
                        Number rankObj = (Number) rs.getObject("fefo_rank");
                        Integer fefoRank = rankObj == null ? null : rankObj.intValue();
                        boolean negative = qty != null && qty.compareTo(BigDecimal.ZERO) < 0;

                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("stock_lot_id", lotId.toString());
                        row.put("batch_id", batchId.toString());
                        row.put("sku_id", skuId.toString());
                        row.put("condition", condition);
                        row.put(
                                "qty_on_hand",
                                qty == null
                                        ? "0.000"
                                        : qty.setScale(3, RoundingMode.HALF_UP).toPlainString());
                        row.put("fefo_rank", fefoRank);
                        row.put("negative", negative);

                        lots.put(lotId, row);
                    }
                    return lots;
                });
    }

    private static Array uuids(Connection c, Collection<UUID> ids) throws SQLException {
        return c.createArrayOf("uuid", ids.toArray(new UUID[0]));
    }
}
